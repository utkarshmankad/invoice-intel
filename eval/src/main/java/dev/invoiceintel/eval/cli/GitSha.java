package dev.invoiceintel.eval.cli;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** The commit an eval runs from, recorded in {@code config.json} (EVAL-4). */
public final class GitSha {

  /** Recorded when the SHA can't be read (no git, not a checkout). */
  public static final String UNKNOWN = "unknown";

  private static final Pattern SHA = Pattern.compile("[0-9a-f]{40,64}");

  private GitSha() {}

  /** {@code git rev-parse HEAD} in the working directory, or {@link #UNKNOWN}. */
  public static String current() {
    return of(List.of("git", "rev-parse", "HEAD"));
  }

  /** The first output line of {@code command} if it is a commit SHA, else {@link #UNKNOWN}. */
  @SuppressFBWarnings(
      value = "COMMAND_INJECTION",
      justification =
          "Only called with the fixed git command above and fixed commands in tests; no user input")
  static String of(List<String> command) {
    try {
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      String output;
      try (InputStream in = process.getInputStream()) {
        output = new String(in.readNBytes(256), StandardCharsets.UTF_8).strip();
      }
      if (!process.waitFor(10, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        return UNKNOWN;
      }
      return process.exitValue() == 0 && SHA.matcher(output).matches() ? output : UNKNOWN;
    } catch (IOException e) {
      return UNKNOWN;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return UNKNOWN;
    }
  }
}
