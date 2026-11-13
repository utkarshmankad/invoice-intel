package dev.invoiceintel.eval.run;

import dev.invoiceintel.eval.metrics.fields.DocumentValuesMapper;
import dev.invoiceintel.eval.metrics.fields.FieldMetrics;
import dev.invoiceintel.eval.metrics.fields.HeaderField;
import dev.invoiceintel.eval.metrics.fields.LineItemCounts;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Runs every document of a dataset through a {@link DocumentRunner}, one after another, and scores
 * each extraction against its label (EVAL-2).
 */
public final class EvalRun {

  private EvalRun() {}

  /**
   * The documents' records, in dataset order, and the model usage of the whole run.
   *
   * @param records one record per document
   * @param usage summed cost, and every model ID and prompt version used
   */
  public record Result(List<DocumentRecord> records, LlmUsage usage) {
    public Result {
      records = List.copyOf(records);
    }
  }

  public static Result execute(List<LabelledDocument> documents, DocumentRunner runner) {
    List<DocumentRecord> records = new ArrayList<>(documents.size());
    LlmUsage total = LlmUsage.NONE;
    for (LabelledDocument document : documents) {
      long start = System.nanoTime();
      DocumentResult result;
      String error = "";
      try {
        result = runner.run(document);
        error = result.error();
      } catch (RuntimeException e) {
        // Only the class name: messages may quote document text (PII-8).
        result = DocumentResult.failed();
        error = e.getClass().getSimpleName();
      }
      long measured = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      // A runner replaying results reports the pipeline time measured where the document ran.
      long latencyMs = result.latencyMs().orElse(measured);
      total = total.plus(result.usage());
      Set<HeaderField> mismatched = null;
      LineItemCounts lineItems = null;
      if (result.extraction().isPresent()) {
        FieldMetrics.DocumentScore score =
            FieldMetrics.scoreDocument(
                new FieldMetrics.DocumentPair(
                    document.sha256(),
                    DocumentValuesMapper.fromLabel(document.label(), runner::labelToken),
                    DocumentValuesMapper.fromExtraction(result.extraction().get())));
        mismatched = score.mismatchedFields();
        lineItems = score.lineItems();
      }
      BigDecimal cost = result.usage().costUsd();
      records.add(
          new DocumentRecord(
              document.sha256(),
              document.synthetic(),
              result.status(),
              result.decision().orElse(null),
              mismatched,
              lineItems,
              cost,
              latencyMs,
              error));
    }
    return new Result(records, total);
  }
}
