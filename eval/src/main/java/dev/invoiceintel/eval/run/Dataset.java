package dev.invoiceintel.eval.run;

import dev.invoiceintel.eval.label.LabelValidator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The label files of a dataset folder (EVAL-1, EVAL-3), checked with {@link LabelValidator}.
 *
 * @param documents the valid labels, sorted by SHA-256
 * @param invalid errors per invalid label file name: JSON paths and rules only, never values
 */
public record Dataset(List<LabelledDocument> documents, Map<String, List<String>> invalid) {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  public Dataset {
    documents = List.copyOf(documents);
    invalid = Map.copyOf(invalid);
  }

  /** Reads every {@code *.json} in {@code dir}. */
  public static Dataset load(Path dir) {
    List<LabelledDocument> documents = new ArrayList<>();
    Map<String, List<String>> invalid = new LinkedHashMap<>();
    for (Path file : jsonFiles(dir)) {
      String name = String.valueOf(file.getFileName());
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
        documents.add(
            new LabelledDocument(
                label.get("sha256").stringValue(), label.get("synthetic").booleanValue(), label));
      } else {
        invalid.put(name, errors);
      }
    }
    return new Dataset(documents, invalid);
  }

  private static List<Path> jsonFiles(Path dir) {
    try (Stream<Path> list = Files.list(dir)) {
      return list.filter(p -> String.valueOf(p.getFileName()).endsWith(".json")).sorted().toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
