package dev.invoiceintel.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.common.ExtractionId;
import dev.invoiceintel.validate.ConfidenceScorer;
import dev.invoiceintel.validate.Decision;
import dev.invoiceintel.validate.DecisionOutcome;
import dev.invoiceintel.validate.DecisionReason;
import dev.invoiceintel.validate.DecisionReason.Code;
import dev.invoiceintel.validate.DecisionStore;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/** Flyway V9: the `decision` table (VAL-8, architecture.md §7) written by `DecisionStore`. */
@SpringBootTest
@ActiveProfiles("test")
@Import({
  TestcontainersConfiguration.class,
  TestAdminConfiguration.class,
  TestPiiKeysConfiguration.class
})
class DecisionMigrationIT {

  private static final Decision REVIEW =
      new Decision(
          new BigDecimal("0.7290"),
          DecisionOutcome.NEEDS_REVIEW,
          List.of(
              new DecisionReason(Code.RULE_WARN, "VAL-4", "seller GSTIN missing"),
              new DecisionReason(Code.LOW_CONFIDENCE, null, "Score 0.7290 below 0.85")));

  @Autowired JdbcClient jdbc;

  private DocumentId documentId;
  private ExtractionId extractionId;
  private DecisionStore store;

  @BeforeEach
  void insertExtraction() {
    jdbc.sql("DELETE FROM document").update();
    documentId = DocumentId.random();
    jdbc.sql(
            "INSERT INTO document (id, sha256, mime_type, status)"
                + " VALUES (?, ?, 'application/pdf', 'EXTRACTED')")
        .params(documentId.value(), "e".repeat(64))
        .update();
    extractionId = ExtractionId.random();
    jdbc.sql(
            "INSERT INTO extraction"
                + " (id, document_id, run_no, strategy, model_id, prompt_version, result)"
                + " VALUES (?, ?, 1, 'TEXT', 'example-local-8b', 'invoice-extraction/v1', '{}')")
        .params(extractionId.value(), documentId.value())
        .update();
    store = new DecisionStore(jdbc);
  }

  @Test
  void replace_thenFind_roundTripsScoreOutcomeAndReasons() {
    store.replace(documentId, extractionId, REVIEW);

    assertThat(store.find(documentId)).contains(REVIEW);
  }

  @Test
  void replace_again_keepsOneRowPerDocument() {
    store.replace(documentId, extractionId, REVIEW);
    var approved = new Decision(new BigDecimal("0.9500"), DecisionOutcome.AUTO_APPROVED, List.of());

    store.replace(documentId, extractionId, approved);

    assertThat(store.find(documentId)).contains(approved);
    assertThat(jdbc.sql("SELECT count(*) FROM decision").query(Long.class).single()).isOne();
  }

  @Test
  void replace_failedWithoutExtraction_isStored() {
    store.replace(documentId, null, ConfidenceScorer.missingExtraction());

    assertThat(store.find(documentId)).contains(ConfidenceScorer.missingExtraction());
  }

  @Test
  void find_otherDocument_isEmpty() {
    store.replace(documentId, extractionId, REVIEW);

    assertThat(store.find(DocumentId.random())).isEmpty();
  }

  @Test
  void insert_reviewWithoutExtraction_isRejected() {
    assertThatThrownBy(() -> insertRow(null, "0.5", "NEEDS_REVIEW", "[]"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void insert_unknownOutcome_isRejected() {
    assertThatThrownBy(() -> insertRow(extractionId.value(), "0.5", "REVIEWED", "[]"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void insert_scoreAboveOne_isRejected() {
    assertThatThrownBy(() -> insertRow(extractionId.value(), "1.5", "AUTO_APPROVED", "[]"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void insert_reasonsNotAnArray_isRejected() {
    assertThatThrownBy(() -> insertRow(extractionId.value(), "0.5", "NEEDS_REVIEW", "{}"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void insert_secondRowForDocument_isRejected() {
    insertRow(extractionId.value(), "0.9", "AUTO_APPROVED", "[]");

    assertThatThrownBy(() -> insertRow(extractionId.value(), "0.9", "AUTO_APPROVED", "[]"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void deleteExtraction_cascadesToDecision() {
    store.replace(documentId, extractionId, REVIEW);

    jdbc.sql("DELETE FROM extraction WHERE id = ?").param(extractionId.value()).update();

    assertThat(store.find(documentId)).isEmpty();
  }

  @Test
  void deleteDocument_cascadesToDecision() {
    store.replace(documentId, null, ConfidenceScorer.missingExtraction());

    jdbc.sql("DELETE FROM document WHERE id = ?").param(documentId.value()).update();

    assertThat(jdbc.sql("SELECT count(*) FROM decision").query(Long.class).single()).isZero();
  }

  private void insertRow(UUID extraction, String score, String outcome, String reasons) {
    jdbc.sql(
            "INSERT INTO decision (id, document_id, extraction_id, score, outcome, reasons)"
                + " VALUES (?, ?, ?, ?, ?, CAST(? AS JSONB))")
        .params(
            UUID.randomUUID(),
            documentId.value(),
            extraction,
            new BigDecimal(score),
            outcome,
            reasons)
        .update();
  }
}
