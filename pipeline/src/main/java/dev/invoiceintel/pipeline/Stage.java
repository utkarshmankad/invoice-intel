package dev.invoiceintel.pipeline;

import java.util.Optional;

/**
 * A per-document pipeline stage (architecture.md §5.2): one JobRunr job that moves a document from
 * its {@link #input()} state to its {@link #output()} state. Declared in pipeline order.
 */
public enum Stage {
  TEXT(DocumentState.RECEIVED, DocumentState.TEXT_READY),
  PII(DocumentState.TEXT_READY, DocumentState.PII_CLEAN),
  EXTRACT(DocumentState.PII_CLEAN, DocumentState.EXTRACTED),
  VALIDATE(DocumentState.EXTRACTED, DocumentState.VALIDATED);

  private final DocumentState input;
  private final DocumentState output;

  Stage(DocumentState input, DocumentState output) {
    this.input = input;
    this.output = output;
  }

  /** The state a document must be in for this stage to run. */
  public DocumentState input() {
    return input;
  }

  /** The state a successful run moves the document to. */
  public DocumentState output() {
    return output;
  }

  /** The stage after this one, or empty for the last stage. */
  public Optional<Stage> next() {
    var stages = values();
    return ordinal() + 1 < stages.length ? Optional.of(stages[ordinal() + 1]) : Optional.empty();
  }
}
