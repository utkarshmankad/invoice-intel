package dev.invoiceintel.pipeline;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.extract.ExtractionStore;
import dev.invoiceintel.pipeline.internal.StageRunRepository;
import dev.invoiceintel.pipeline.internal.ValidateStageRepository;
import dev.invoiceintel.validate.AmountInWordsRule;
import dev.invoiceintel.validate.ConfidenceScorer;
import dev.invoiceintel.validate.Decision;
import dev.invoiceintel.validate.DecisionStore;
import dev.invoiceintel.validate.GrandTotalRule;
import dev.invoiceintel.validate.GstinStateRule;
import dev.invoiceintel.validate.RuleEngine;
import dev.invoiceintel.validate.SubtotalRule;
import dev.invoiceintel.validate.TaxAmountRule;
import dev.invoiceintel.validate.TaxRegimeDateRule;
import dev.invoiceintel.validate.TaxSplitRule;
import dev.invoiceintel.validate.ValidationContext;
import dev.invoiceintel.validate.ValidationResult;
import dev.invoiceintel.validate.ValidationResultStore;
import java.math.BigDecimal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The Validate stage, EXTRACTED → AUTO_APPROVED or NEEDS_REVIEW (VAL-1..8, architecture.md §5.2,
 * §6.6, ADR-20): runs the rules on the extraction of the latest succeeded Extract run, scores it
 * and stores the {@code validation_result} rows and the {@code decision} in the stage transaction.
 * The decision picks the document's next state, so {@code VALIDATED} is never a resting state.
 */
public final class ValidateStageHandler implements StageHandler {

  /** No succeeded Extract run, or its {@code extraction} row is gone (VAL-8: FAILED). */
  public static final String EXTRACTION_MISSING = "EXTRACTION_MISSING";

  private static final Logger log = LoggerFactory.getLogger(ValidateStageHandler.class);

  private final StageRunRepository runs;
  private final ValidateStageRepository documents;
  private final ExtractionStore extractions;
  private final ValidationResultStore results;
  private final DecisionStore decisions;
  private final ConfidenceScorer scorer;
  private final RuleEngine engine =
      new RuleEngine(
          List.of(
              new SubtotalRule(),
              new TaxAmountRule(),
              new GrandTotalRule(),
              new GstinStateRule(),
              new TaxSplitRule(),
              new TaxRegimeDateRule(),
              new AmountInWordsRule()));

  /**
   * @param approveThreshold the VAL-8 auto-approval threshold, {@code
   *     invoice-intel.validate.approve-threshold}
   */
  public ValidateStageHandler(JdbcClient jdbc, BigDecimal approveThreshold) {
    this.runs = new StageRunRepository(jdbc);
    this.documents = new ValidateStageRepository(jdbc);
    this.extractions = new ExtractionStore(jdbc);
    this.results = new ValidationResultStore(jdbc);
    this.decisions = new DecisionStore(jdbc);
    this.scorer = new ConfidenceScorer(approveThreshold);
  }

  @Override
  public Stage stage() {
    return Stage.VALIDATE;
  }

  @Override
  public void run(DocumentId documentId, int runNo) {
    advance(documentId, runNo);
  }

  @Override
  public DocumentState advance(DocumentId documentId, int runNo) {
    ExtractionStore.Stored extraction =
        runs.latestSucceededRun(documentId, Stage.EXTRACT).stream()
            .boxed()
            .findFirst()
            .flatMap(extractRun -> extractions.find(documentId, extractRun))
            .orElseThrow(() -> new StageFailedException(EXTRACTION_MISSING));
    var context = new ValidationContext(documentId, documents.documentClass(documentId));
    List<ValidationResult> ruleResults = engine.run(extraction.result(), context);
    Decision decision = scorer.decide(extraction.result(), ruleResults, context);
    results.replace(extraction.id(), ruleResults);
    decisions.replace(documentId, extraction.id(), decision);
    // PipelineService has set documentId, stage and runNo in the MDC. Outcome and score only:
    // reason messages name fields and amounts (PII-8).
    log.atInfo()
        .addKeyValue("decision", decision.outcome())
        .addKeyValue("score", decision.score())
        .addKeyValue("reasons", decision.reasons().size())
        .log("decision stored");
    return switch (decision.outcome()) {
      case AUTO_APPROVED -> DocumentState.AUTO_APPROVED;
      case NEEDS_REVIEW -> DocumentState.NEEDS_REVIEW;
      // The scorer gives FAILED only for a missing extraction, which is caught above.
      case FAILED -> throw new StageFailedException(EXTRACTION_MISSING);
    };
  }
}
