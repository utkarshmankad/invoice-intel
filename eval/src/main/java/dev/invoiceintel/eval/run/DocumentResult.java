package dev.invoiceintel.eval.run;

import dev.invoiceintel.common.extraction.InvoiceExtraction;
import java.util.Objects;
import java.util.Optional;

/**
 * What a {@link DocumentRunner} produced for one document: the extraction the metrics (EVAL-2)
 * compare with the label, the VAL-8 decision and the model usage (cost, model IDs, prompt
 * versions).
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

  private static final DocumentResult SKIPPED =
      new DocumentResult(Status.SKIPPED, null, null, LlmUsage.NONE);

  private final Status status;
  private final InvoiceExtraction extraction;
  private final Decision decision;
  private final LlmUsage usage;

  private DocumentResult(
      Status status, InvoiceExtraction extraction, Decision decision, LlmUsage usage) {
    this.status = status;
    this.extraction = extraction;
    this.decision = decision;
    this.usage = usage;
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
    return new DocumentResult(Status.EXTRACTED, extraction, decision, usage);
  }

  /** A document the runner didn't run. */
  public static DocumentResult skipped() {
    return SKIPPED;
  }

  /** A document the pipeline failed on, after making the model calls in {@code usage}. */
  public static DocumentResult failed(LlmUsage usage) {
    return new DocumentResult(
        Status.FAILED, null, Decision.FAILED, Objects.requireNonNull(usage, "usage"));
  }

  /** A document the pipeline failed on before any model call. */
  public static DocumentResult failed() {
    return failed(LlmUsage.NONE);
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
}
