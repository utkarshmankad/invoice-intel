package dev.invoiceintel.eval.run;

/**
 * Runs nothing and marks every document {@link DocumentResult.Status#SKIPPED}. It lets {@code eval
 * run} load, check and walk a dataset without an LLM or the local stack (M5.2), until the
 * in-process pipeline runner is wired.
 */
public final class StubDocumentRunner implements DocumentRunner {

  public static final String NAME = "stub";

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public DocumentResult run(LabelledDocument document) {
    return DocumentResult.skipped();
  }
}
