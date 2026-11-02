package dev.invoiceintel.pipeline;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.extract.ExtractionStore;
import dev.invoiceintel.pipeline.internal.DocumentDetailsRepository;
import dev.invoiceintel.pipeline.internal.StageRunRepository;
import dev.invoiceintel.validate.Decision;
import dev.invoiceintel.validate.DecisionStore;
import dev.invoiceintel.validate.ValidationResultStore;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reads {@link DocumentDetails} for the document API (UC2, architecture.md §8). The pipeline owns
 * which run of a stage is current, so the reads that join the Extract and Validate outputs live
 * here rather than in {@code app}. Never reads {@code text_layer}, {@code pii_span}, the vault or
 * object storage.
 */
public final class DocumentDetailsReader {

  private final DocumentDetailsRepository documents;
  private final StageRunRepository runs;
  private final ExtractionStore extractions;
  private final DecisionStore decisions;
  private final ValidationResultStore results;
  private final TransactionTemplate tx;

  public DocumentDetailsReader(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
    this.documents = new DocumentDetailsRepository(jdbc);
    this.runs = new StageRunRepository(jdbc);
    this.extractions = new ExtractionStore(jdbc);
    this.decisions = new DecisionStore(jdbc);
    this.results = new ValidationResultStore(jdbc);
    var template = new TransactionTemplate(Objects.requireNonNull(transactionManager, "tx"));
    // One snapshot, so a stage committing between the reads can't mix two runs' outputs.
    template.setReadOnly(true);
    template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    this.tx = template;
  }

  /** The document's details, or empty if there is none with {@code id}. */
  public Optional<DocumentDetails> find(DocumentId id) {
    Objects.requireNonNull(id, "id");
    return Objects.requireNonNull(tx.execute(_ -> read(id)));
  }

  private Optional<DocumentDetails> read(DocumentId id) {
    var row = documents.find(id);
    if (row.isEmpty()) {
      return Optional.empty();
    }
    var document = row.get();
    Optional<ExtractionStore.Stored> extraction =
        runs.latestSucceededRun(id, Stage.EXTRACT).stream()
            .boxed()
            .findFirst()
            .flatMap(run -> extractions.find(id, run));
    // The decision row outlives a reprocess; it is current only while a Validate run succeeded.
    Optional<DocumentDetails.Decision> decision =
        runs.latestSucceededRun(id, Stage.VALIDATE).isPresent()
            ? decisions.find(id).map(DocumentDetailsReader::decision)
            : Optional.empty();
    List<DocumentDetails.RuleCheck> rules =
        decision.isEmpty() || extraction.isEmpty()
            ? List.of()
            : results.find(extraction.get().id()).stream()
                .map(
                    r -> new DocumentDetails.RuleCheck(r.ruleId(), r.outcome().name(), r.message()))
                .toList();
    return Optional.of(
        new DocumentDetails(
            id,
            document.state(),
            document.documentClass(),
            document.failedStage(),
            document.failureCode(),
            extraction.map(ExtractionStore.Stored::result).orElse(null),
            decision.orElse(null),
            rules));
  }

  private static DocumentDetails.Decision decision(Decision decision) {
    return new DocumentDetails.Decision(
        decision.score(),
        decision.outcome().name(),
        decision.reasons().stream()
            .map(r -> new DocumentDetails.Reason(r.code().name(), r.ruleId(), r.message()))
            .toList());
  }
}
