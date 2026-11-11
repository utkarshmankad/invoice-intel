package dev.invoiceintel.eval.run;

/**
 * Runs one labelled document through a pipeline configuration (EVAL-1). The stub runner is the only
 * implementation so far; the in-process pipeline runner (architecture.md §6.9) and the stubbed-LLM
 * smoke runner (M5.9) plug in here.
 */
public interface DocumentRunner {

  /** A short name recorded in the run's {@code config.json} (EVAL-4). */
  String name();

  /**
   * Runs the document. An exception counts as a {@link DocumentResult.Status#FAILED} document; only
   * its class name is recorded, since messages may hold document text (PII-8).
   */
  DocumentResult run(LabelledDocument document);

  /**
   * The token the PII stage gives a printed invoice or order number, so the label's {@code
   * invoiceNumber}, {@code orderNumber} and {@code originalInvoiceRef} compare with the
   * pseudonymized extraction (architecture.md §9.2). The pipeline runner tokenizes with the run's
   * {@code TOKEN_KEYSET}; the default leaves the value as printed, for runners whose extractions
   * hold printed values.
   */
  default String labelToken(String printed) {
    return printed;
  }
}
