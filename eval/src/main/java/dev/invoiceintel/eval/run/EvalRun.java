package dev.invoiceintel.eval.run;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs every document of a dataset through a {@link DocumentRunner}, one after another. */
public final class EvalRun {

  private EvalRun() {}

  /** One record per document, in dataset order. */
  public static List<DocumentRecord> execute(
      List<LabelledDocument> documents, DocumentRunner runner) {
    List<DocumentRecord> records = new ArrayList<>(documents.size());
    for (LabelledDocument document : documents) {
      long start = System.nanoTime();
      DocumentResult.Status status;
      String error = "";
      try {
        status = runner.run(document).status();
      } catch (RuntimeException e) {
        // Only the class name: messages may quote document text (PII-8).
        status = DocumentResult.Status.FAILED;
        error = e.getClass().getSimpleName();
      }
      long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      records.add(
          new DocumentRecord(document.sha256(), document.synthetic(), status, latencyMs, error));
    }
    return List.copyOf(records);
  }
}
