package dev.invoiceintel.validate;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.common.ExtractionId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads and writes a document's {@code decision} row (VAL-8, architecture.md §7, V9). A document
 * has at most one: deciding again replaces it, so it always describes the latest Validate run; call
 * {@link #replace} inside the stage transaction.
 */
public final class DecisionStore {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final TypeReference<List<DecisionReason>> REASONS = new TypeReference<>() {};

  private final JdbcClient jdbc;

  public DecisionStore(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  /**
   * @param extractionId the extraction decided on, or {@code null} for a {@link
   *     DecisionOutcome#FAILED} decision without one
   */
  public void replace(DocumentId documentId, ExtractionId extractionId, Decision decision) {
    jdbc.sql("DELETE FROM decision WHERE document_id = ?").param(documentId.value()).update();
    jdbc.sql(
            """
            INSERT INTO decision (id, document_id, extraction_id, score, outcome, reasons)
            VALUES (?, ?, ?, ?, ?, CAST(? AS JSONB))
            """)
        .params(
            UUID.randomUUID(),
            documentId.value(),
            extractionId == null ? null : extractionId.value(),
            decision.score(),
            decision.outcome().name(),
            JSON.writeValueAsString(decision.reasons()))
        .update();
  }

  public Optional<Decision> find(DocumentId documentId) {
    return jdbc.sql("SELECT score, outcome, reasons FROM decision WHERE document_id = ?")
        .param(documentId.value())
        .query(
            (rs, row) ->
                new Decision(
                    rs.getBigDecimal("score"),
                    DecisionOutcome.valueOf(rs.getString("outcome")),
                    JSON.readValue(rs.getString("reasons"), REASONS)))
        .optional();
  }
}
