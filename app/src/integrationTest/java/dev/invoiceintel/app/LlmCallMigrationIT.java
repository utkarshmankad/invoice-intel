package dev.invoiceintel.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.extract.JdbcLlmCallLog;
import dev.invoiceintel.extract.LlmCall;
import dev.invoiceintel.extract.LlmOutcome;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/** Flyway V6: the `llm_call` table (EXT-4, architecture.md §7) written by `JdbcLlmCallLog`. */
@SpringBootTest
@ActiveProfiles("test")
@Import({
  TestcontainersConfiguration.class,
  TestAdminConfiguration.class,
  TestPiiKeysConfiguration.class
})
class LlmCallMigrationIT {

  @Autowired JdbcClient jdbc;

  private DocumentId documentId;

  @BeforeEach
  void insertDocument() {
    jdbc.sql("DELETE FROM document").update();
    documentId = DocumentId.random();
    jdbc.sql(
            "INSERT INTO document (id, sha256, mime_type, status)"
                + " VALUES (?, ?, 'application/pdf', 'RECEIVED')")
        .params(documentId.value(), "c".repeat(64))
        .update();
  }

  private LlmCall call(LlmOutcome outcome, int attempt) {
    return new LlmCall(
        documentId,
        "EXTRACT",
        "ollama",
        "example-local-8b",
        "invoice-text/v1",
        attempt,
        1200,
        300,
        new BigDecimal("0.004800"),
        850,
        outcome);
  }

  @Test
  void record_storesEveryColumn() {
    new JdbcLlmCallLog(jdbc).record(call(LlmOutcome.SUCCEEDED, 1));

    Map<String, Object> row =
        jdbc.sql(
                "SELECT stage, provider, model_id, prompt_version, attempt, input_tokens,"
                    + " output_tokens, cost_usd, latency_ms, outcome FROM llm_call"
                    + " WHERE document_id = ?")
            .param(documentId.value())
            .query()
            .singleRow();
    assertThat(row)
        .containsEntry("stage", "EXTRACT")
        .containsEntry("provider", "ollama")
        .containsEntry("model_id", "example-local-8b")
        .containsEntry("prompt_version", "invoice-text/v1")
        .containsEntry("attempt", 1)
        .containsEntry("input_tokens", 1200)
        .containsEntry("output_tokens", 300)
        .containsEntry("latency_ms", 850L)
        .containsEntry("outcome", "SUCCEEDED");
    assertThat((BigDecimal) row.get("cost_usd")).isEqualByComparingTo("0.0048");
  }

  @Test
  void record_attemptBelowOne_isRejected() {
    var log = new JdbcLlmCallLog(jdbc);

    assertThatThrownBy(() -> log.record(call(LlmOutcome.ERROR, 0)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void deleteDocument_cascadesToCalls() {
    new JdbcLlmCallLog(jdbc).record(call(LlmOutcome.INVALID_OUTPUT, 1));

    jdbc.sql("DELETE FROM document WHERE id = ?").param(documentId.value()).update();

    assertThat(jdbc.sql("SELECT count(*) FROM llm_call").query(Long.class).single()).isZero();
  }
}
