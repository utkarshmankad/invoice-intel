package dev.invoiceintel.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.invoiceintel.common.DocumentClass;
import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.common.ExtractionId;
import dev.invoiceintel.pipeline.DocumentDetails;
import dev.invoiceintel.pipeline.ReviewQueueItem;
import dev.invoiceintel.pipeline.ReviewQueueReader;
import dev.invoiceintel.pipeline.ReviewQueueReader.Sort;
import dev.invoiceintel.validate.Decision;
import dev.invoiceintel.validate.DecisionOutcome;
import dev.invoiceintel.validate.DecisionReason;
import dev.invoiceintel.validate.DecisionReason.Code;
import dev.invoiceintel.validate.DecisionStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/** REV-1, architecture.md §6.8: the review queue reads NEEDS_REVIEW documents with reasons. */
@SpringBootTest
@ActiveProfiles("test")
@Import({
  TestcontainersConfiguration.class,
  TestAdminConfiguration.class,
  TestPiiKeysConfiguration.class
})
class ReviewQueueReaderIT {

  private static final Instant T0 = Instant.parse("2026-01-10T08:00:00Z");

  @Autowired JdbcClient jdbc;
  @Autowired ReviewQueueReader reader;

  private int seq;

  @BeforeEach
  void clear() {
    jdbc.sql("DELETE FROM document").update();
  }

  @Test
  void list_onlyNeedsReview_withReasonsInStoredOrder() {
    var reasons =
        List.of(
            new DecisionReason(Code.RULE_FAIL, "VAL-5", "tax total differs by 1.00"),
            new DecisionReason(Code.RULE_WARN, "VAL-4", "seller GSTIN missing"),
            new DecisionReason(Code.LOW_CONFIDENCE, null, "Score 0.4100 below 0.85"));
    var queued = document("NEEDS_REVIEW", T0, DocumentClass.TAX_INVOICE);
    decide(queued, "0.4100", DecisionOutcome.NEEDS_REVIEW, reasons);
    var approved = document("AUTO_APPROVED", T0.plusSeconds(60), DocumentClass.TAX_INVOICE);
    decide(approved, "0.9500", DecisionOutcome.AUTO_APPROVED, List.of());
    document("EXTRACTED", T0.plusSeconds(120), null);

    var queue = reader.list(Sort.RECEIVED, false);

    assertThat(queue)
        .containsExactly(
            new ReviewQueueItem(
                queued,
                T0,
                DocumentClass.TAX_INVOICE,
                new BigDecimal("0.4100"),
                List.of(
                    new DocumentDetails.Reason("RULE_FAIL", "VAL-5", "tax total differs by 1.00"),
                    new DocumentDetails.Reason("RULE_WARN", "VAL-4", "seller GSTIN missing"),
                    new DocumentDetails.Reason(
                        "LOW_CONFIDENCE", null, "Score 0.4100 below 0.85"))));
  }

  @Test
  void list_withoutDecision_hasNoScoreOrReasons() {
    var queued = document("NEEDS_REVIEW", T0, null);

    var queue = reader.list(Sort.RECEIVED, false);

    assertThat(queue).containsExactly(new ReviewQueueItem(queued, T0, null, null, List.of()));
  }

  @Test
  void list_byReceived_ordersByUploadTimeInEitherDirection() {
    var middle = queued(T0.plusSeconds(60), "0.5000");
    var oldest = queued(T0, "0.7000");
    var newest = queued(T0.plusSeconds(120), "0.6000");

    assertThat(ids(reader.list(Sort.RECEIVED, false))).containsExactly(oldest, middle, newest);
    assertThat(ids(reader.list(Sort.RECEIVED, true))).containsExactly(newest, middle, oldest);
  }

  @Test
  void list_byScore_ordersByScore_tiesOldestFirst_noDecisionLast() {
    var high = queued(T0, "0.8000");
    var lowNewer = queued(T0.plusSeconds(120), "0.3000");
    var lowOlder = queued(T0.plusSeconds(60), "0.3000");
    var undecided = document("NEEDS_REVIEW", T0.minusSeconds(60), null);

    assertThat(ids(reader.list(Sort.SCORE, false)))
        .containsExactly(lowOlder, lowNewer, high, undecided);
    assertThat(ids(reader.list(Sort.SCORE, true)))
        .containsExactly(high, lowOlder, lowNewer, undecided);
  }

  @Test
  void list_moreThanLimit_returnsFirstLimitDocuments() {
    for (int i = 0; i <= ReviewQueueReader.LIMIT; i++) {
      queued(T0.plusSeconds(i), "0.5000");
    }

    var queue = reader.list(Sort.RECEIVED, false);

    assertThat(queue).hasSize(ReviewQueueReader.LIMIT);
    assertThat(queue.getFirst().receivedAt()).isEqualTo(T0);
    assertThat(queue.getLast().receivedAt())
        .isEqualTo(T0.plusSeconds(ReviewQueueReader.LIMIT - 1L));
  }

  private DocumentId queued(Instant receivedAt, String score) {
    var id = document("NEEDS_REVIEW", receivedAt, DocumentClass.TAX_INVOICE);
    decide(
        id,
        score,
        DecisionOutcome.NEEDS_REVIEW,
        List.of(new DecisionReason(Code.LOW_CONFIDENCE, null, "Score below 0.85")));
    return id;
  }

  private DocumentId document(String status, Instant createdAt, DocumentClass documentClass) {
    var id = DocumentId.random();
    jdbc.sql(
            "INSERT INTO document (id, sha256, mime_type, status, document_class, created_at)"
                + " VALUES (?, ?, 'application/pdf', ?, ?, ?)")
        .params(
            id.value(),
            "%064x".formatted(++seq),
            status,
            documentClass == null ? null : documentClass.name(),
            java.sql.Timestamp.from(createdAt.truncatedTo(ChronoUnit.MICROS)))
        .update();
    return id;
  }

  private void decide(
      DocumentId id, String score, DecisionOutcome outcome, List<DecisionReason> reasons) {
    var extractionId = ExtractionId.random();
    jdbc.sql(
            "INSERT INTO extraction"
                + " (id, document_id, run_no, strategy, model_id, prompt_version, result)"
                + " VALUES (?, ?, 1, 'TEXT', 'example-local-8b', 'invoice-extraction/v1', '{}')")
        .params(extractionId.value(), id.value())
        .update();
    new DecisionStore(jdbc)
        .replace(id, extractionId, new Decision(new BigDecimal(score), outcome, reasons));
  }

  private static List<DocumentId> ids(List<ReviewQueueItem> queue) {
    return queue.stream().map(ReviewQueueItem::id).toList();
  }
}
