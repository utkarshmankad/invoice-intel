package dev.invoiceintel.eval.run;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A run's output folder {@code <out>/<runId>/} (architecture.md §6.9): {@code config.json} (EVAL-4)
 * and {@code documents.csv}. Both hold identifiers, settings and timings only, never label or
 * extraction values, so the folder may be committed to {@code eval-reports/}.
 *
 * @param config the run's configuration
 * @param documents one record per document
 */
public record RunDirectory(RunConfig config, List<DocumentRecord> documents) {

  static final String CONFIG = "config.json";
  static final String DOCUMENTS = "documents.csv";
  static final String CSV_HEADER = "sha256,synthetic,status,latency_ms,error";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  public RunDirectory {
    documents = List.copyOf(documents);
  }

  /** Writes the run into {@code dir}, which must not exist yet. */
  public void write(Path dir) {
    try {
      Path parent = dir.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.createDirectory(dir);
      ObjectNode json = JSON.createObjectNode();
      json.put("runId", config.runId());
      json.put("startedAt", config.startedAt().toString());
      json.put("dataset", config.dataset());
      json.put("strategy", config.strategy());
      json.put("model", config.model());
      json.put("runner", config.runner());
      json.put("documents", documents.size());
      Files.writeString(
          dir.resolve(CONFIG),
          JSON.writerWithDefaultPrettyPrinter().writeValueAsString(json) + "\n",
          StandardCharsets.UTF_8);
      List<String> lines = new ArrayList<>(documents.size() + 1);
      lines.add(CSV_HEADER);
      for (DocumentRecord d : documents) {
        lines.add(
            String.join(
                ",",
                d.sha256(),
                Boolean.toString(d.synthetic()),
                d.status().name(),
                Long.toString(d.latencyMs()),
                d.error()));
      }
      Files.write(dir.resolve(DOCUMENTS), lines, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Reads a run written by {@link #write(Path)}.
   *
   * @throws IllegalArgumentException if {@code dir} isn't a run folder
   */
  public static RunDirectory read(Path dir) {
    Path config = dir.resolve(CONFIG);
    Path documents = dir.resolve(DOCUMENTS);
    if (!Files.isRegularFile(config) || !Files.isRegularFile(documents)) {
      throw new IllegalArgumentException(
          "Not a run folder (needs " + CONFIG + " and " + DOCUMENTS + "): " + dir);
    }
    try {
      JsonNode json = JSON.readTree(Files.readString(config, StandardCharsets.UTF_8));
      RunConfig runConfig =
          new RunConfig(
              text(json, "runId"),
              Instant.parse(text(json, "startedAt")),
              text(json, "dataset"),
              text(json, "strategy"),
              text(json, "model"),
              text(json, "runner"));
      List<String> lines = Files.readAllLines(documents, StandardCharsets.UTF_8);
      List<DocumentRecord> records = new ArrayList<>();
      for (String line : lines.subList(1, lines.size())) {
        if (line.isBlank()) {
          continue;
        }
        String[] cells = line.split(",", -1);
        if (cells.length != 5) {
          throw new IllegalArgumentException("Malformed row in " + DOCUMENTS + ": " + dir);
        }
        records.add(
            new DocumentRecord(
                cells[0],
                Boolean.parseBoolean(cells[1]),
                DocumentResult.Status.valueOf(cells[2]),
                Long.parseLong(cells[3]),
                cells[4]));
      }
      return new RunDirectory(runConfig, records);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String text(JsonNode json, String key) {
    JsonNode node = json.get(key);
    if (node == null || !node.isString()) {
      throw new IllegalArgumentException(CONFIG + " has no text value for " + key);
    }
    return node.stringValue();
  }
}
