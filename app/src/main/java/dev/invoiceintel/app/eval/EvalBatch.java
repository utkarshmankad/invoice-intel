package dev.invoiceintel.app.eval;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.ingest.IngestResult;
import dev.invoiceintel.ingest.IngestService;
import dev.invoiceintel.pipeline.DocumentDetails;
import dev.invoiceintel.pipeline.DocumentDetailsReader;
import dev.invoiceintel.pipeline.DocumentState;
import dev.invoiceintel.pipeline.PipelineService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs a folder of originals through the pipeline in-process and writes what {@code eval run
 * --results} scores (architecture.md §6.9, ADR-24). Each original is ingested and run with {@link
 * PipelineService#runNow}, in the calling thread with no JobRunr queue wait, so the recorded time
 * is pipeline time (NFR-3). The result file {@code <sha256>.json} is keyed by the SHA-256 of the
 * original's bytes, as label files are (§9.2), so this side never reads labels and the eval never
 * reads originals.
 *
 * <p>A result holds the status, the VAL-8 decision, the stage and failure code of a failed
 * document, the pipeline time, the model usage summed over the document's {@code llm_call} rows and
 * the current extraction (pseudonymized, as the model returned it). Logs carry counts only (PII-8).
 */
public final class EvalBatch {

  private static final Logger log = LoggerFactory.getLogger(EvalBatch.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final IngestService ingest;
  private final PipelineService pipeline;
  private final DocumentDetailsReader details;
  private final JdbcClient jdbc;

  public EvalBatch(
      IngestService ingest,
      PipelineService pipeline,
      DocumentDetailsReader details,
      JdbcClient jdbc) {
    this.ingest = Objects.requireNonNull(ingest, "ingest");
    this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
    this.details = Objects.requireNonNull(details, "details");
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  /** How many originals ran, and how many were already in the database and skipped. */
  public record Summary(int ran, int duplicates) {}

  /**
   * Runs every regular file in {@code input} (not recursive, in name order) and writes one result
   * file per original into {@code output}. An original already in the database is skipped and gets
   * no result file (the eval then counts it as skipped); run against an empty database.
   */
  public Summary run(Path input, Path output) {
    if (!Files.isDirectory(input)) {
      throw new IllegalArgumentException("eval batch input is not a folder");
    }
    List<Path> files;
    try (Stream<Path> listing = Files.list(input)) {
      files = listing.filter(Files::isRegularFile).sorted().toList();
      Files.createDirectories(output);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    int ran = 0;
    int duplicates = 0;
    for (Path file : files) {
      if (runOne(file, output)) {
        ran++;
      } else {
        duplicates++;
      }
    }
    log.info("eval batch finished: {} ran, {} duplicates skipped", ran, duplicates);
    return new Summary(ran, duplicates);
  }

  private boolean runOne(Path file, Path output) {
    byte[] bytes;
    try {
      bytes = Files.readAllBytes(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    String sha256 = sha256(bytes);
    IngestResult ingested = ingest.ingest(List.of(bytes)).getFirst();
    if (ingested.duplicate()) {
      return false;
    }
    DocumentId id = ingested.documentId();
    long started = System.nanoTime();
    pipeline.runNow(id);
    long latencyMs = (System.nanoTime() - started) / 1_000_000;
    DocumentDetails document =
        details.find(id).orElseThrow(() -> new IllegalStateException("document vanished"));
    write(output.resolve(sha256 + ".json"), result(sha256, document, latencyMs));
    return true;
  }

  private Map<String, Object> result(String sha256, DocumentDetails document, long latencyMs) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("sha256", sha256);
    boolean decided =
        document.extraction() != null
            && (document.state() == DocumentState.AUTO_APPROVED
                || document.state() == DocumentState.NEEDS_REVIEW);
    if (decided) {
      result.put("status", "EXTRACTED");
      result.put("decision", document.state().name());
      result.put("error", "");
    } else {
      // FAILED, or a chain that stopped early; the eval scores both as failed.
      result.put("status", "FAILED");
      result.put("decision", "FAILED");
      result.put("error", error(document));
    }
    result.put("latencyMs", latencyMs);
    result.put("usage", usage(document.id()));
    result.put("extraction", decided ? document.extraction() : null);
    return result;
  }

  private static String error(DocumentDetails document) {
    if (document.failedStage() == null) {
      return "STOPPED_IN " + document.state().name();
    }
    return document.failedStage().name() + " " + Objects.toString(document.failureCode(), "");
  }

  private Map<String, Object> usage(DocumentId id) {
    BigDecimal cost =
        jdbc.sql("SELECT COALESCE(SUM(cost_usd), 0) FROM llm_call WHERE document_id = ?")
            .param(id.value())
            .query(BigDecimal.class)
            .single();
    Map<String, Object> usage = new LinkedHashMap<>();
    usage.put("costUsd", cost.toPlainString());
    usage.put("modelIds", distinct("model_id", id));
    usage.put("promptVersions", distinct("prompt_version", id));
    return usage;
  }

  private List<String> distinct(String column, DocumentId id) {
    // Column names are this class's constants, never input.
    return jdbc.sql("SELECT DISTINCT " + column + " FROM llm_call WHERE document_id = ? ORDER BY 1")
        .param(id.value())
        .query(String.class)
        .list();
  }

  private static void write(Path file, Map<String, Object> result) {
    try {
      Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
