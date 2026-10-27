package dev.invoiceintel.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/** Flyway V1: the `document` table of architecture.md §7. */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestAdminConfiguration.class})
class DocumentMigrationIT {

  private static final String SHA_A = "a".repeat(64);
  private static final String SHA_B = "b".repeat(64);

  @Autowired JdbcClient jdbc;

  @BeforeEach
  void clean() {
    jdbc.sql("DELETE FROM document").update();
  }

  @Test
  void insert_receivedDocument_fillsTimestamps() {
    insert(SHA_A, "RECEIVED", null, null);

    var createdAtSet =
        jdbc.sql("SELECT created_at IS NOT NULL AND updated_at IS NOT NULL FROM document")
            .query(Boolean.class)
            .single();
    assertThat(createdAtSet).isTrue();
  }

  @Test
  void insert_duplicateSha256_isRejected() {
    insert(SHA_A, "RECEIVED", null, null);

    assertThatThrownBy(() -> insert(SHA_A, "RECEIVED", null, null))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void insert_failedWithStageAndCode_isAccepted() {
    insert(SHA_A, "FAILED", "PII", "PII_LEAK");

    assertThat(jdbc.sql("SELECT count(*) FROM document").query(Long.class).single()).isOne();
  }

  @ParameterizedTest
  @CsvSource(
      nullValues = "null",
      value = {
        "UNKNOWN, null, null", // not a state from §5.1
        "FAILED, null, null", // FAILED without stage and code
        "FAILED, PII, null", // FAILED without code
        "RECEIVED, PII, PII_LEAK", // failure fields on a non-failed document
      })
  void insert_invalidStatusOrFailureFields_isRejected(
      String status, String failedStage, String failureCode) {
    assertThatThrownBy(() -> insert(SHA_B, status, failedStage, failureCode))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void insert_uppercaseSha256_isRejected() {
    assertThatThrownBy(() -> insert("A".repeat(64), "RECEIVED", null, null))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private void insert(String sha256, String status, String failedStage, String failureCode) {
    jdbc.sql(
            """
            INSERT INTO document (id, sha256, mime_type, status, failed_stage, failure_code)
            VALUES (?, ?, 'application/pdf', ?, ?, ?)
            """)
        .params(UUID.randomUUID(), sha256, status, failedStage, failureCode)
        .update();
  }
}
