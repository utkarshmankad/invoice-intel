package dev.invoiceintel.pipeline;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.extract.ExtractionStore;
import dev.invoiceintel.extract.InvoiceExtraction;
import dev.invoiceintel.extract.InvoiceExtractor;
import dev.invoiceintel.extract.LlmResult;
import dev.invoiceintel.pii.LeakChecker;
import dev.invoiceintel.pii.RedactedContent;
import dev.invoiceintel.pii.RedactedContentLoader;
import dev.invoiceintel.pipeline.internal.StageRunRepository;
import java.util.Objects;
import java.util.OptionalInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The Extract stage, PII_CLEAN → EXTRACTED (EXT-1..5, PII-6, architecture.md §5.2, §6.5): loads the
 * {@link RedactedContent} of the latest succeeded PII run, asks the configured model for the
 * invoice fields, runs the leak check on every raw response and stores the {@code extraction} row.
 *
 * <p>The leak check runs before anything of the response is stored, also when the model's output
 * was invalid: a leak fails the document with {@code PII_LEAK} whatever the parse result.
 */
public final class ExtractStageHandler implements StageHandler {

  /** No succeeded PII run, or its text layer or a masked page image is gone. */
  public static final String REDACTED_CONTENT_MISSING = "REDACTED_CONTENT_MISSING";

  private static final Logger log = LoggerFactory.getLogger(ExtractStageHandler.class);

  private final StageRunRepository runs;
  private final RedactedContentLoader contents;
  private final InvoiceExtractor extractor;
  private final LeakChecker leakChecker;
  private final ExtractionStore store;

  public ExtractStageHandler(
      JdbcClient jdbc,
      RedactedContentLoader contents,
      InvoiceExtractor extractor,
      LeakChecker leakChecker,
      ExtractionStore store) {
    this.runs = new StageRunRepository(jdbc);
    this.contents = Objects.requireNonNull(contents, "contents");
    this.extractor = Objects.requireNonNull(extractor, "extractor");
    this.leakChecker = Objects.requireNonNull(leakChecker, "leakChecker");
    this.store = Objects.requireNonNull(store, "store");
  }

  @Override
  public Stage stage() {
    return Stage.EXTRACT;
  }

  @Override
  public void run(DocumentId documentId, int runNo) {
    RedactedContent content = load(documentId);
    LlmResult<InvoiceExtraction> result = extractor.extract(content);
    for (String response : result.responses()) {
      if (leakChecker.check(documentId, response).leaked()) {
        throw new StageFailedException(LeakChecker.FAILURE_CODE);
      }
    }
    switch (result) {
      case LlmResult.Failed<InvoiceExtraction> failed ->
          throw new StageFailedException(failed.code());
      case LlmResult.Succeeded<InvoiceExtraction> succeeded -> {
        store.save(
            documentId, runNo, extractor.request(), extractor.model().modelId(), succeeded.value());
        // PipelineService has set documentId, stage and runNo in the MDC.
        log.atInfo()
            .addKeyValue("model", extractor.model().name())
            .addKeyValue("strategy", extractor.request().strategy())
            .addKeyValue("calls", succeeded.calls().size())
            .log("extraction stored");
      }
    }
  }

  private RedactedContent load(DocumentId documentId) {
    OptionalInt piiRun = runs.latestSucceededRun(documentId, Stage.PII);
    if (piiRun.isEmpty()) {
      throw new StageFailedException(REDACTED_CONTENT_MISSING);
    }
    try {
      return contents
          .load(documentId, piiRun.getAsInt())
          .orElseThrow(() -> new StageFailedException(REDACTED_CONTENT_MISSING));
    } catch (IllegalArgumentException e) {
      throw new StageFailedException(REDACTED_CONTENT_MISSING);
    }
  }
}
