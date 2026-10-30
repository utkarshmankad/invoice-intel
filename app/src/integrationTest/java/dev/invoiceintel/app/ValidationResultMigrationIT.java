package dev.invoiceintel.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.common.ExtractionId;
import dev.invoiceintel.validate.RuleOutcome;
import dev.invoiceintel.validate.ValidationResult;
import dev.invoiceintel.validate.ValidationResultStore;
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

/**
 * Flyway V8: the `validation_result` table (VAL-1..7, architecture.md §7) written by
 * `ValidationResultStore`.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({
  TestcontainersConfiguration.class,
  TestAdminConfiguration.class,
  TestPiiKeysConfiguration.class
})
class ValidationResultMigrationIT {

  @Autowired JdbcClient jdbc;

  private DocumentId documentId;
  private ExtractionId extractionId;
  private ValidationResultStore store;

  @BeforeEach
  void insertExtraction() {
    jdbc.sql("DELETE FROM document").update();
    documentId = DocumentId.random();
    jdbc.sql(
            "INSERT INTO document (id, sha256, mime_type, status)"
                + " VALUES (?, ?, 'application/pdf', 'EXTRACTED')")
        .params(documentId.value(), "d".repeat(64))
        .update();
    extractionId = ExtractionId.random();
    jdbc.sql(
            "INSERT INTO extraction"
                + " (id, document_id, run_no, strategy, model_id, prompt_version, result)"
                + " VALUES (?, ?, 1, 'TEXT', 'example-local-8b', 'invoice-extraction/v1', '{}')")
        .params(extractionId.value(), documentId.value())
        .update();
    store = new ValidationResultStore(jdbc);
  }

  @Test
  void replace_thenFind_returnsResultsOrderedByRuleId() {
    var results =
        List.of(
            new ValidationResult("VAL-3", RuleOutcome.FAIL, "grand total off by 5.00"),
            new ValidationResult("VAL-1", RuleOutcome.PASS, null),
            new ValidationResult("VAL-2", RuleOutcome.WARN, "rate unusual"));

    store.replace(extractionId, results);

    assertThat(store.find(extractionId))
        .containsExactly(results.get(1), results.get(2), results.get(0));
  }

  @Test
  void replace_again_replacesEarlierRows() {
    store.replace(extractionId, List.of(new ValidationResult("VAL-1", RuleOutcome.FAIL, "off")));

    store.replace(extractionId, List.of(new ValidationResult("VAL-1", RuleOutcome.PASS, null)));

    assertThat(store.find(extractionId))
        .containsExactly(new ValidationResult("VAL-1", RuleOutcome.PASS, null));
  }

  @Test
  void find_otherExtraction_isEmpty() {
    store.replace(extractionId, List.of(new ValidationResult("VAL-1", RuleOutcome.PASS, null)));

    assertThat(store.find(ExtractionId.random())).isEmpty();
  }

  @Test
  void insert_passWithMessage_isRejected() {
    assertThatThrownBy(() -> insertRow("VAL-1", "PASS", "fine"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void insert_failWithoutMessage_isRejected() {
    assertThatThrownBy(() -> insertRow("VAL-1", "FAIL", null))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void insert_unknownOutcome_isRejected() {
    assertThatThrownBy(() -> insertRow("VAL-1", "SKIPPED", null))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void insert_sameRuleTwice_isRejected() {
    insertRow("VAL-1", "PASS", null);

    assertThatThrownBy(() -> insertRow("VAL-1", "PASS", null))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void deleteDocument_cascadesThroughExtractionToResults() {
    store.replace(extractionId, List.of(new ValidationResult("VAL-1", RuleOutcome.PASS, null)));

    jdbc.sql("DELETE FROM document WHERE id = ?").param(documentId.value()).update();

    assertThat(jdbc.sql("SELECT count(*) FROM validation_result").query(Long.class).single())
        .isZero();
  }

  private void insertRow(String ruleId, String outcome, String message) {
    jdbc.sql(
            "INSERT INTO validation_result (id, extraction_id, rule_id, outcome, message)"
                + " VALUES (?, ?, ?, ?, ?)")
        .params(UUID.randomUUID(), extractionId.value(), ruleId, outcome, message)
        .update();
  }
}
