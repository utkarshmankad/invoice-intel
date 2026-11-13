package dev.invoiceintel.eval.run;

import dev.invoiceintel.eval.metrics.fields.FieldCounts;
import dev.invoiceintel.eval.metrics.fields.HeaderField;
import dev.invoiceintel.eval.metrics.fields.LineItemCounts;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A run's output folder {@code <out>/<runId>/} (architecture.md §6.9): {@code config.json}
 * (EVAL-4), {@code documents.csv}, {@code fields.csv} and {@code report.md} (EVAL-1, EVAL-2). All
 * hold identifiers, settings, field names, counts, costs and timings only, never label or
 * extraction values, so the folder may be committed to {@code eval-reports/}. A run scored from the
 * app's eval batch also holds {@code reconciliation.json} ({@link ReconciliationCounts}, ADR-26).
 *
 * @param config the run's configuration
 * @param documents one record per document
 * @param reconciliation the eval batch's reconciliation counts, or null when the run has none
 */
public record RunDirectory(
    RunConfig config, List<DocumentRecord> documents, ReconciliationCounts reconciliation) {

  static final String CONFIG = "config.json";
  static final String DOCUMENTS = "documents.csv";
  static final String FIELDS = "fields.csv";
  static final String REPORT = "report.md";
  static final String CSV_HEADER =
      "sha256,synthetic,status,decision,correct,mismatched_fields,"
          + "line_tp,line_fp,line_fn,cost_usd,latency_ms,error";
  static final String FIELDS_HEADER = "field,correct,total,accuracy";

  private static final int COLUMNS = CSV_HEADER.split(",").length;
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Map<String, HeaderField> BY_LABEL_KEY =
      Arrays.stream(HeaderField.values())
          .collect(Collectors.toMap(HeaderField::labelKey, Function.identity()));

  public RunDirectory {
    documents = List.copyOf(documents);
  }

  /** A run without reconciliation counts. */
  public RunDirectory(RunConfig config, List<DocumentRecord> documents) {
    this(config, documents, null);
  }

  /** The run-level metrics of the documents. */
  public RunSummary summary() {
    return RunSummary.of(documents);
  }

  /** Writes the run into {@code dir}, which must not exist yet. */
  public void write(Path dir) {
    try {
      Path parent = dir.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.createDirectory(dir);
      RunSummary summary = summary();
      Files.writeString(dir.resolve(CONFIG), configJson(), StandardCharsets.UTF_8);
      Files.write(dir.resolve(DOCUMENTS), documentLines(), StandardCharsets.UTF_8);
      Files.write(dir.resolve(FIELDS), fieldLines(summary), StandardCharsets.UTF_8);
      Files.writeString(
          dir.resolve(REPORT),
          RunReport.markdown(config, summary, reconciliation),
          StandardCharsets.UTF_8);
      if (reconciliation != null) {
        reconciliation.write(dir);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private String configJson() {
    ObjectNode json = JSON.createObjectNode();
    json.put("runId", config.runId());
    json.put("startedAt", config.startedAt().toString());
    json.put("dataset", config.dataset());
    json.put("strategy", config.strategy());
    json.put("model", config.model());
    json.put("runner", config.runner());
    json.put("gitSha", config.gitSha());
    ArrayNode models = json.putArray("modelIds");
    config.modelIds().forEach(models::add);
    ArrayNode prompts = json.putArray("promptVersions");
    config.promptVersions().forEach(prompts::add);
    if (config.usdInr() == null) {
      json.putNull("usdInr");
    } else {
      json.put("usdInr", config.usdInr().toPlainString());
    }
    json.put("documents", documents.size());
    return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(json) + "\n";
  }

  private List<String> documentLines() {
    List<String> lines = new ArrayList<>(documents.size() + 1);
    lines.add(CSV_HEADER);
    for (DocumentRecord d : documents) {
      LineItemCounts items = d.lineItems();
      lines.add(
          String.join(
              ",",
              d.sha256(),
              Boolean.toString(d.synthetic()),
              d.status().name(),
              d.decision() == null ? "" : d.decision().name(),
              d.scored() ? Boolean.toString(d.correct()) : "",
              d.scored()
                  ? d.mismatchedFields().stream()
                      .map(HeaderField::labelKey)
                      .collect(Collectors.joining(";"))
                  : "",
              items == null ? "" : Integer.toString(items.truePositives()),
              items == null ? "" : Integer.toString(items.falsePositives()),
              items == null ? "" : Integer.toString(items.falseNegatives()),
              d.costUsd().toPlainString(),
              Long.toString(d.latencyMs()),
              d.error()));
    }
    return lines;
  }

  private static List<String> fieldLines(RunSummary summary) {
    List<String> lines = new ArrayList<>(HeaderField.values().length + 1);
    lines.add(FIELDS_HEADER);
    for (HeaderField field : HeaderField.values()) {
      FieldCounts counts = summary.perField().get(field);
      lines.add(
          String.join(
              ",",
              field.labelKey(),
              Integer.toString(counts.correct()),
              Integer.toString(counts.total()),
              counts.total() == 0 ? "" : String.format(Locale.ROOT, "%.4f", counts.accuracy())));
    }
    return lines;
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
      JsonNode usdInr = json.get("usdInr");
      RunConfig runConfig =
          new RunConfig(
              text(json, "runId"),
              Instant.parse(text(json, "startedAt")),
              text(json, "dataset"),
              text(json, "strategy"),
              text(json, "model"),
              text(json, "runner"),
              text(json, "gitSha"),
              texts(json, "modelIds"),
              texts(json, "promptVersions"),
              usdInr == null || usdInr.isNull() ? null : new BigDecimal(usdInr.stringValue()));
      List<String> lines = Files.readAllLines(documents, StandardCharsets.UTF_8);
      if (lines.isEmpty() || !lines.get(0).equals(CSV_HEADER)) {
        throw new IllegalArgumentException("Unexpected header in " + DOCUMENTS + ": " + dir);
      }
      List<DocumentRecord> records = new ArrayList<>();
      for (String line : lines.subList(1, lines.size())) {
        if (!line.isBlank()) {
          records.add(record(line, dir));
        }
      }
      return new RunDirectory(runConfig, records, ReconciliationCounts.read(dir).orElse(null));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static DocumentRecord record(String line, Path dir) {
    String[] c = line.split(",", -1);
    if (c.length != COLUMNS) {
      throw new IllegalArgumentException("Malformed row in " + DOCUMENTS + ": " + dir);
    }
    try {
      boolean scored = !c[4].isEmpty();
      Set<HeaderField> mismatched = null;
      LineItemCounts items = null;
      if (scored) {
        mismatched = EnumSet.noneOf(HeaderField.class);
        for (String key : c[5].isEmpty() ? new String[0] : c[5].split(";")) {
          HeaderField field = BY_LABEL_KEY.get(key);
          if (field == null) {
            throw new IllegalArgumentException("Unknown field in " + DOCUMENTS + ": " + dir);
          }
          mismatched.add(field);
        }
        items =
            new LineItemCounts(
                Integer.parseInt(c[6]), Integer.parseInt(c[7]), Integer.parseInt(c[8]));
      }
      return new DocumentRecord(
          c[0],
          Boolean.parseBoolean(c[1]),
          DocumentResult.Status.valueOf(c[2]),
          c[3].isEmpty() ? null : Decision.valueOf(c[3]),
          mismatched,
          items,
          new BigDecimal(c[9]),
          Long.parseLong(c[10]),
          c[11]);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Malformed row in " + DOCUMENTS + ": " + dir, e);
    }
  }

  private static String text(JsonNode json, String key) {
    JsonNode node = json.get(key);
    if (node == null || !node.isString()) {
      throw new IllegalArgumentException(CONFIG + " has no text value for " + key);
    }
    return node.stringValue();
  }

  private static List<String> texts(JsonNode json, String key) {
    JsonNode node = json.get(key);
    if (node == null || !node.isArray()) {
      throw new IllegalArgumentException(CONFIG + " has no list for " + key);
    }
    List<String> values = new ArrayList<>();
    for (JsonNode value : node) {
      values.add(value.stringValue());
    }
    return values;
  }
}
