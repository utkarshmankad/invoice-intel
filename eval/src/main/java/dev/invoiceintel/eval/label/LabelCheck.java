package dev.invoiceintel.eval.label;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks a folder of label files (plan D5, architecture.md §9.2) and, optionally, that each label
 * has its original in a raw folder. The owner runs it on {@code data/labels/}; it prints label file
 * names (SHA-256 hashes), JSON paths, rules and counts only, never a value or a raw file name,
 * since both may hold personal data (.claude/rules/pii.md).
 *
 * <pre>./gradlew :eval:checkLabels --args="data/labels data/raw"</pre>
 */
public final class LabelCheck {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private LabelCheck() {}

  public static void main(String[] args) {
    if (args.length < 1 || args.length > 2) {
      System.err.println("usage: LabelCheck <labels-dir> [raw-dir]");
      System.exit(2);
    }
    Path raw = args.length == 2 ? Path.of(args[1]) : null;
    System.exit(run(Path.of(args[0]), raw, System.out));
  }

  /** Checks every {@code *.json} in {@code labels}; returns the exit code (0 = all valid). */
  static int run(Path labels, Path raw, PrintStream out) {
    if (!Files.isDirectory(labels)) {
      out.println("Not a folder: " + labels);
      return 2;
    }
    List<Path> files = jsonFiles(labels);
    int invalid = 0;
    int synthetic = 0;
    for (Path file : files) {
      String name = name(file);
      List<String> errors;
      JsonNode label = null;
      try {
        label = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
        errors = LabelValidator.validate(label, name);
      } catch (JacksonException e) {
        // The parser's message can quote the file; report the position only.
        errors =
            List.of(
                "$: not valid JSON (line "
                    + e.getLocation().getLineNr()
                    + ", column "
                    + e.getLocation().getColumnNr()
                    + ")");
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      if (errors.isEmpty()) {
        if (label.get("synthetic").booleanValue()) {
          synthetic++;
        }
      } else {
        invalid++;
        out.println(name + ":");
        errors.forEach(error -> out.println("  " + error));
      }
    }
    int missing = raw == null ? 0 : checkOriginals(files, raw, out);
    out.printf(
        "%d label files: %d valid (%d real, %d synthetic), %d with errors%n",
        files.size(),
        files.size() - invalid,
        files.size() - invalid - synthetic,
        synthetic,
        invalid);
    return invalid == 0 && missing == 0 ? 0 : 1;
  }

  /** Labels whose original isn't in {@code raw}, matched by SHA-256 of the bytes. */
  private static int checkOriginals(List<Path> labels, Path raw, PrintStream out) {
    if (!Files.isDirectory(raw)) {
      out.println("Not a folder: " + raw);
      return 1;
    }
    Set<String> originals;
    try (Stream<Path> walk = Files.walk(raw)) {
      originals =
          walk.filter(Files::isRegularFile)
              .filter(p -> !name(p).startsWith("."))
              .map(LabelCheck::sha256)
              .collect(Collectors.toSet());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    var withoutOriginal =
        labels.stream()
            .map(LabelCheck::name)
            .filter(name -> !originals.contains(name.replaceFirst("\\.json$", "")))
            .toList();
    withoutOriginal.forEach(
        name -> out.println(name + ": no file in the raw folder has this SHA-256"));
    out.printf(
        "%d raw files, %d without a label%n",
        originals.size(), originals.size() - (labels.size() - withoutOriginal.size()));
    return withoutOriginal.size();
  }

  private static List<Path> jsonFiles(Path dir) {
    try (Stream<Path> list = Files.list(dir)) {
      return list.filter(p -> name(p).endsWith(".json")).sorted().toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String name(Path file) {
    Path name = file.getFileName();
    return name == null ? "" : name.toString();
  }

  static String sha256(Path file) {
    MessageDigest digest = digest();
    try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
      in.transferTo(OutputStream.nullOutputStream());
      return HexFormat.of().formatHex(digest.digest());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static MessageDigest digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
