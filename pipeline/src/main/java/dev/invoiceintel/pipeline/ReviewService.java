package dev.invoiceintel.pipeline;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.common.extraction.InvoiceExtraction;
import dev.invoiceintel.extract.ExtractionStore;
import dev.invoiceintel.extract.FieldCorrections;
import dev.invoiceintel.pipeline.internal.FieldCorrectionRepository;
import dev.invoiceintel.pipeline.internal.StageRunRepository;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Field corrections and approval of documents in review (REV-3, architecture.md §6.8, ADR-30). Both
 * work only on a document in {@code NEEDS_REVIEW}, under its row lock, so they never interleave
 * with a stage run or a reprocess. A correction stores a {@code field_correction} row per changed
 * field and decides again: the VAL-1..7 rules and the VAL-8 score run on the corrected extraction
 * and replace the rule results and decision. Approving moves the document to {@code REVIEWED}.
 */
public final class ReviewService {

  private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

  private final StageRunRepository runs;
  private final ExtractionStore extractions;
  private final FieldCorrectionRepository corrections;
  private final ValidateStageHandler validator;
  private final TransactionTemplate tx;

  /**
   * @param approveThreshold the VAL-8 auto-approval threshold, as for the Validate stage
   */
  public ReviewService(
      JdbcClient jdbc, PlatformTransactionManager transactionManager, BigDecimal approveThreshold) {
    this.runs = new StageRunRepository(jdbc);
    this.extractions = new ExtractionStore(jdbc);
    this.corrections = new FieldCorrectionRepository(jdbc);
    this.validator = new ValidateStageHandler(jdbc, approveThreshold);
    this.tx = new TransactionTemplate(Objects.requireNonNull(transactionManager, "tx"));
  }

  /**
   * Applies {@code values} (field path → new value) to the document's current extraction and
   * decides again. Fields whose value doesn't change are skipped; nothing is stored when none
   * changes.
   *
   * @return the number of fields corrected; {@code -1} for an unknown document
   * @throws ReviewNotAllowedException if the document isn't in {@code NEEDS_REVIEW} or has no
   *     extraction
   * @throws InvalidCorrectionException for a path or value that can't be applied; nothing is stored
   *     then
   */
  public int correct(DocumentId id, Map<String, String> values, String correctedBy) {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(values, "values");
    Objects.requireNonNull(correctedBy, "correctedBy");
    Integer changed;
    try {
      changed = store(id, values, correctedBy);
    } catch (FieldCorrections.InvalidCorrectionException e) {
      throw new InvalidCorrectionException(e.getMessage(), e);
    }
    int count = Objects.requireNonNull(changed);
    if (count > 0) {
      log.atInfo()
          .addKeyValue("documentId", id.value())
          .addKeyValue("fields", count)
          .log("fields corrected");
    }
    return count;
  }

  /** Whether a reviewer may correct the field at {@code path}; PII token fields can't be. */
  public static boolean correctable(String path) {
    return !FieldCorrections.TOKEN_FIELDS.contains(path);
  }

  private Integer store(DocumentId id, Map<String, String> values, String correctedBy) {
    return tx.execute(
        _ -> {
          if (!lockInReview(id)) {
            return -1;
          }
          ExtractionStore.Stored stored = currentExtraction(id);
          InvoiceExtraction current = corrections.current(stored.id(), stored.result());
          int count = 0;
          for (var entry : values.entrySet()) {
            String path = entry.getKey();
            String oldValue = FieldCorrections.value(current, path).orElse(null);
            String newValue = entry.getValue() == null ? null : entry.getValue().strip();
            if (oldValue != null && oldValue.equals(newValue)) {
              // Unchanged, including token fields the form shows read-only.
              continue;
            }
            // Throws for an unknown path, a token field or a value of the wrong type.
            current = FieldCorrections.apply(current, path, newValue);
            corrections.insert(id, stored.id(), path, oldValue, newValue, correctedBy);
            count++;
          }
          if (count > 0) {
            validator.decide(id, stored.id(), current);
          }
          return count;
        });
  }

  /**
   * Moves the document from {@code NEEDS_REVIEW} to {@code REVIEWED}.
   *
   * @return false for an unknown document
   * @throws ReviewNotAllowedException if the document isn't in {@code NEEDS_REVIEW}
   */
  public boolean approve(DocumentId id) {
    Objects.requireNonNull(id, "id");
    boolean found =
        Boolean.TRUE.equals(
            tx.execute(
                _ -> {
                  if (!lockInReview(id)) {
                    return false;
                  }
                  runs.setState(id, DocumentState.REVIEWED);
                  return true;
                }));
    if (found) {
      log.atInfo().addKeyValue("documentId", id.value()).log("document approved");
    }
    return found;
  }

  private boolean lockInReview(DocumentId id) {
    var locked = runs.lockDocument(id);
    if (locked.isEmpty()) {
      return false;
    }
    DocumentState state = locked.get().state();
    if (state != DocumentState.NEEDS_REVIEW) {
      throw new ReviewNotAllowedException("document is " + state + ", not NEEDS_REVIEW");
    }
    return true;
  }

  private ExtractionStore.Stored currentExtraction(DocumentId id) {
    return runs.latestSucceededRun(id, Stage.EXTRACT).stream()
        .boxed()
        .findFirst()
        .flatMap(run -> extractions.find(id, run))
        .orElseThrow(() -> new ReviewNotAllowedException("document has no extraction"));
  }
}
