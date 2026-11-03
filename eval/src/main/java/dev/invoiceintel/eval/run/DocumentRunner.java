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
}
