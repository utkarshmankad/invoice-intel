package dev.invoiceintel.extract;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.common.ExtractionId;
import dev.invoiceintel.common.extraction.InvoiceExtraction;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes and reads the {@code extraction} row of an Extract run (architecture.md §7, V7). The
 * result holds pseudonymized values only (specs.md §6); the caller stores it only after the leak
 * check passed (PII-6) and inside the stage transaction.
 */
public final class ExtractionStore {

  /** A stored extraction and the ID its validation results and decision refer to. */
  public record Stored(ExtractionId id, InvoiceExtraction result) {

    public Stored {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(result, "result");
    }
  }

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final JdbcClient jdbc;

  public ExtractionStore(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  /**
   * @param modelId the provider's model ID ({@link ModelSpec#modelId()})
   */
  public void save(
      DocumentId documentId,
      int runNo,
      ExtractionRequest<?> request,
      String modelId,
      InvoiceExtraction result) {
    jdbc.sql(
            """
            INSERT INTO extraction
              (id, document_id, run_no, strategy, model_id, prompt_version, result)
            VALUES (?, ?, ?, ?, ?, ?, CAST(? AS JSONB))
            """)
        .params(
            UUID.randomUUID(),
            documentId.value(),
            runNo,
            request.strategy().name(),
            modelId,
            request.prompt().id(),
            JSON.writeValueAsString(result))
        .update();
  }

  /** The extraction of the document's Extract run {@code runNo}, if that run stored one. */
  public Optional<Stored> find(DocumentId documentId, int runNo) {
    return jdbc.sql("SELECT id, result FROM extraction WHERE document_id = ? AND run_no = ?")
        .params(documentId.value(), runNo)
        .query(
            (rs, row) ->
                new Stored(
                    new ExtractionId(rs.getObject("id", UUID.class)),
                    JSON.readValue(rs.getString("result"), InvoiceExtraction.class)))
        .optional();
  }
}
