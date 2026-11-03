package dev.invoiceintel.eval.run;

import dev.invoiceintel.common.extraction.InvoiceExtraction;
import java.util.Objects;
import java.util.Optional;

/**
 * What a {@link DocumentRunner} produced for one document. The metrics (EVAL-2) compare the
 * extraction with the label.
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

  private static final DocumentResult SKIPPED = new DocumentResult(Status.SKIPPED, null);

  private final Status status;
  private final InvoiceExtraction extraction;

  private DocumentResult(Status status, InvoiceExtraction extraction) {
    this.status = status;
    this.extraction = extraction;
  }

  /** A document the pipeline extracted. */
  public static DocumentResult extracted(InvoiceExtraction extraction) {
    return new DocumentResult(Status.EXTRACTED, Objects.requireNonNull(extraction, "extraction"));
  }

  /** A document the runner didn't run. */
  public static DocumentResult skipped() {
    return SKIPPED;
  }

  /** A document the pipeline failed on. */
  public static DocumentResult failed() {
    return new DocumentResult(Status.FAILED, null);
  }

  public Status status() {
    return status;
  }

  /** The extraction, present only when {@link #status()} is {@link Status#EXTRACTED}. */
  public Optional<InvoiceExtraction> extraction() {
    return Optional.ofNullable(extraction);
  }
}
