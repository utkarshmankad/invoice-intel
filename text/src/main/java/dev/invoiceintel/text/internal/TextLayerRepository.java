package dev.invoiceintel.text.internal;

import dev.invoiceintel.common.DocumentId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Rows of {@code text_layer} (architecture.md §7); the layer is passed as JSON text. */
public final class TextLayerRepository {

  private final JdbcClient jdbc;

  public TextLayerRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  public void insert(DocumentId documentId, int runNo, String layerJson) {
    jdbc.sql(
            """
            INSERT INTO text_layer (id, document_id, run_no, layer)
            VALUES (?, ?, ?, CAST(? AS JSONB))
            """)
        .params(UUID.randomUUID(), documentId.value(), runNo, layerJson)
        .update();
  }

  /** JSON of the layer from the highest run, if any. */
  public Optional<String> latest(DocumentId documentId) {
    return jdbc.sql(
            """
            SELECT layer::text FROM text_layer WHERE document_id = ?
            ORDER BY run_no DESC LIMIT 1
            """)
        .param(documentId.value())
        .query(String.class)
        .optional();
  }
}
