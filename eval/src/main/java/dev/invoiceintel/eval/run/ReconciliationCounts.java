package dev.invoiceintel.eval.run;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The reconciliation counts the app's eval batch writes next to its results ({@value #FILE},
 * architecture.md §6.9, ADR-26): rows per status, documents and matched documents. The eval copies
 * them into the run folder and reports the spec §9 match rate. Counts only.
 *
 * @param documents documents in all reconciliation rows
 * @param matchedDocuments documents in {@code MATCHED} rows
 * @param orders rows per status name, in the file's order
 */
public record ReconciliationCounts(
    int documents, int matchedDocuments, Map<String, Integer> orders) {

  /** The file name, in a results folder and in a run folder. */
  public static final String FILE = "reconciliation.json";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  public ReconciliationCounts {
    if (documents < 0 || matchedDocuments < 0 || matchedDocuments > documents) {
      throw new IllegalArgumentException("inconsistent reconciliation counts");
    }
    orders = Collections.unmodifiableMap(new LinkedHashMap<>(orders));
  }

  /** Matched documents over all documents; empty when there are none. */
  public OptionalDouble matchRate() {
    return documents == 0
        ? OptionalDouble.empty()
        : OptionalDouble.of((double) matchedDocuments / documents);
  }

  /**
   * Reads {@value #FILE} in {@code folder}; empty when there is none.
   *
   * @throws IllegalArgumentException if the file has an unknown shape
   */
  public static Optional<ReconciliationCounts> read(Path folder) {
    Path file = folder.resolve(FILE);
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    JsonNode json;
    try {
      json = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (JacksonException e) {
      throw new IllegalArgumentException(FILE + " is not JSON", e);
    }
    JsonNode orders = json.path("orders");
    if (!json.path("documents").isInt()
        || !json.path("matchedDocuments").isInt()
        || !orders.isObject()) {
      throw new IllegalArgumentException(FILE + " needs documents, matchedDocuments and orders");
    }
    Map<String, Integer> byStatus = new LinkedHashMap<>();
    for (var entry : orders.properties()) {
      if (!entry.getValue().isInt()) {
        throw new IllegalArgumentException(FILE + " has a non-integer count");
      }
      byStatus.put(entry.getKey(), entry.getValue().intValue());
    }
    return Optional.of(
        new ReconciliationCounts(
            json.get("documents").intValue(), json.get("matchedDocuments").intValue(), byStatus));
  }

  /** Writes {@value #FILE} into {@code folder}. */
  public void write(Path folder) {
    ObjectNode json = JSON.createObjectNode();
    json.put("documents", documents);
    json.put("matchedDocuments", matchedDocuments);
    ObjectNode byStatus = json.putObject("orders");
    orders.forEach(byStatus::put);
    try {
      Files.writeString(
          folder.resolve(FILE),
          JSON.writerWithDefaultPrettyPrinter().writeValueAsString(json) + "\n",
          StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
