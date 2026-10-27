package dev.invoiceintel.common.logging;

import java.util.Objects;
import java.util.UUID;
import org.slf4j.MDC;

/**
 * Puts the document ID and pipeline stage into the SLF4J MDC for the duration of a block, so every
 * log line of that block carries them as structured fields (NFR-6). Only IDs, stages and run
 * numbers belong here, never field values or text (PII-8).
 *
 * <pre>{@code
 * try (var _ = LogContext.forStage(documentId, "PII")) {
 *   log.info("stage started");
 * }
 * }</pre>
 */
public final class LogContext implements AutoCloseable {

  public static final String DOCUMENT_ID = "documentId";
  public static final String STAGE = "stage";
  public static final String RUN_NO = "runNo";

  private final String[] keys;

  private LogContext(String... keys) {
    this.keys = keys;
  }

  /** Adds {@code documentId} only, e.g. for ingest before a stage runs. */
  public static LogContext forDocument(UUID documentId) {
    MDC.put(DOCUMENT_ID, Objects.requireNonNull(documentId, "documentId").toString());
    return new LogContext(DOCUMENT_ID);
  }

  /** Adds {@code documentId} and {@code stage}. */
  public static LogContext forStage(UUID documentId, String stage) {
    Objects.requireNonNull(stage, "stage");
    forDocument(documentId);
    MDC.put(STAGE, stage);
    return new LogContext(DOCUMENT_ID, STAGE);
  }

  /** Adds {@code documentId}, {@code stage} and the stage {@code runNo}. */
  public static LogContext forStageRun(UUID documentId, String stage, int runNo) {
    forStage(documentId, stage);
    MDC.put(RUN_NO, Integer.toString(runNo));
    return new LogContext(DOCUMENT_ID, STAGE, RUN_NO);
  }

  /** Removes the keys this context added. */
  @Override
  public void close() {
    for (String key : keys) {
      MDC.remove(key);
    }
  }
}
