package dev.invoiceintel.eval.run;

import dev.invoiceintel.common.extraction.InvoiceExtraction;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.regex.Pattern;

/**
 * What a {@link DocumentRunner} produced for one document: the extraction the metrics (EVAL-2)
 * compare with the label, the VAL-8 decision and the model usage (cost, model IDs, prompt
 * versions). A runner that replays results measured elsewhere also carries the pipeline time
 * ({@link #latencyMs()}) and, for a failed document, its stage and failure code ({@link #error()}).
 */
public final class DocumentResult {

  /** Outcome of running one document. */
  public enum Status {
    /** The pipeline produced an extraction. */
    EXTRACTED,
    /** The runner didn't run the document (stub runner). */
    SKIPPED,
    /** The pipeline failed before producing an extraction. */
    FAILED
  }

  /** Codes only, such as {@code EXTRACT LEAK_DETECTED}; never text (PII-8). */
  private static final Pattern ERROR_CODE = Pattern.compile("[A-Za-z0-9_ .-]{0,80}");

  private static final DocumentResult SKIPPED =
      new DocumentResult(Status.SKIPPED, null, null, LlmUsage.NONE, null, "");

  private final Status status;
  private final InvoiceExtraction extraction;
  private final Decision decision;
  private final LlmUsage usage;
  private final Long latencyMs;
  private final String error;

  private DocumentResult(
      Status status,
      InvoiceExtraction extraction,
      Decision decision,
      LlmUsage usage,
      Long latencyMs,
      String error) {
    this.status = status;
    this.extraction = extraction;
    this.decision = decision;
    this.usage = usage;
    this.latencyMs = latencyMs;
    this.error = error;
  }

  /**
   * A document the pipeline extracted and decided on.
   *
   * @param decision {@link Decision#AUTO_APPROVED} or {@link Decision#NEEDS_REVIEW}; an extracted
   *     document can't be {@link Decision#FAILED} (use {@link #failed(LlmUsage)})
   */
  public static DocumentResult extracted(
      InvoiceExtraction extraction, Decision decision, LlmUsage usage) {
    Objects.requireNonNull(extraction, "extraction");
    Objects.requireNonNull(decision, "decision");
    Objects.requireNonNull(usage, "usage");
    if (decision == Decision.FAILED) {
      throw new IllegalArgumentException("an extracted document is not FAILED");
    }
    return new DocumentResult(Status.EXTRACTED, extraction, decision, usage, null, "");
  }

  /** A document the runner didn't run. */
  public static DocumentResult skipped() {
    return SKIPPED;
  }

  /** A document the pipeline failed on, after making the model calls in {@code usage}. */
  public static DocumentResult failed(LlmUsage usage) {
    return failed(usage, "");
  }

  /**
   * A document the pipeline failed on, with the stage and failure code it recorded.
   *
   * @param error stage and code, letters, digits, {@code _ . -} and spaces only (PII-8)
   */
  public static DocumentResult failed(LlmUsage usage, String error) {
    Objects.requireNonNull(error, "error");
    if (!ERROR_CODE.matcher(error).matches()) {
      throw new IllegalArgumentException("error must be a stage and failure code");
    }
    return new DocumentResult(
        Status.FAILED, null, Decision.FAILED, Objects.requireNonNull(usage, "usage"), null, error);
  }

  /** A document the pipeline failed on before any model call. */
  public static DocumentResult failed() {
    return failed(LlmUsage.NONE);
  }

  /**
   * This result with the pipeline time measured where the document ran; the eval reports it instead
   * of the runner's own wall time.
   */
  public DocumentResult withLatencyMs(long latencyMs) {
    if (latencyMs < 0) {
      throw new IllegalArgumentException("latencyMs must not be negative");
    }
    return new DocumentResult(status, extraction, decision, usage, latencyMs, error);
  }

  public Status status() {
    return status;
  }

  /** The extraction, present only when {@link #status()} is {@link Status#EXTRACTED}. */
  public Optional<InvoiceExtraction> extraction() {
    return Optional.ofNullable(extraction);
  }

  /** The decision; empty only for a skipped document. */
  public Optional<Decision> decision() {
    return Optional.ofNullable(decision);
  }

  public LlmUsage usage() {
    return usage;
  }

  /** The pipeline time measured where the document ran; empty when the runner ran it itself. */
  public OptionalLong latencyMs() {
    return latencyMs == null ? OptionalLong.empty() : OptionalLong.of(latencyMs);
  }

  /** Stage and failure code of a failed document, or empty. */
  public String error() {
    return error;
  }
}
