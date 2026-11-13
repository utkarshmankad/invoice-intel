package dev.invoiceintel.eval.run;

import dev.invoiceintel.common.extraction.InvoiceExtraction;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Replays the results the app's eval batch wrote (architecture.md §6.9, ADR-24): the pipeline ran
 * in {@code app}, which writes one {@code <sha256>.json} per original into a results folder, and
 * this runner hands each labelled document its file. A label without a result file is {@link
 * DocumentResult.Status#SKIPPED}. The runner never sees the originals or the stack.
 */
public final class ResultsDocumentRunner implements DocumentRunner {

  public static final String NAME = "results";

  /** Amounts keep the scale the app wrote (1040.00, not 1040.0). */
  private static final JsonMapper JSON =
      JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

  private final Path folder;

  public ResultsDocumentRunner(Path folder) {
    this.folder = Objects.requireNonNull(folder, "folder");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public DocumentResult run(LabelledDocument document) {
    Path file = folder.resolve(document.sha256() + ".json");
    if (!Files.isRegularFile(file)) {
      return DocumentResult.skipped();
    }
    JsonNode node;
    try {
      node = JSON.readTree(Files.readString(file));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return parse(node);
  }

  /** Reads one result file; throws {@link IllegalArgumentException} on an unknown shape. */
  static DocumentResult parse(JsonNode node) {
    LlmUsage usage = usage(node.path("usage"));
    String status = node.path("status").asString("");
    DocumentResult result =
        switch (status) {
          case "EXTRACTED" -> {
            JsonNode extraction = node.path("extraction");
            if (!extraction.isObject()) {
              throw new IllegalArgumentException("EXTRACTED result without an extraction");
            }
            yield DocumentResult.extracted(
                JSON.treeToValue(extraction, InvoiceExtraction.class),
                Decision.valueOf(node.path("decision").asString("")),
                usage);
          }
          case "FAILED" -> DocumentResult.failed(usage, node.path("error").asString(""));
          default -> throw new IllegalArgumentException("unknown result status");
        };
    JsonNode latency = node.path("latencyMs");
    return latency.isIntegralNumber() ? result.withLatencyMs(latency.asLong()) : result;
  }

  private static LlmUsage usage(JsonNode usage) {
    if (!usage.isObject()) {
      return LlmUsage.NONE;
    }
    return new LlmUsage(
        new BigDecimal(usage.path("costUsd").asString("0")),
        strings(usage.path("modelIds")),
        strings(usage.path("promptVersions")));
  }

  private static Set<String> strings(JsonNode array) {
    Set<String> values = new TreeSet<>();
    array.forEach(value -> values.add(value.asString()));
    return values;
  }
}
