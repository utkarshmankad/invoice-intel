package dev.invoiceintel.pipeline.internal;

import dev.invoiceintel.common.DocumentClass;
import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.storage.BlobKey;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The {@code document} and {@code blob_ref} reads and writes of the Text stage. */
public final class TextStageRepository {

  /** Where the uploaded file is and its detected MIME type. */
  public record Original(String mimeType, BlobKey key) {}

  private final JdbcClient jdbc;

  public TextStageRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  public Optional<Original> original(DocumentId documentId) {
    return jdbc.sql(
            """
            SELECT d.mime_type, b.bucket, b.key FROM document d
            JOIN blob_ref b ON b.document_id = d.id AND b.kind = 'ORIGINAL'
            WHERE d.id = ?
            """)
        .param(documentId.value())
        .query(
            (rs, row) ->
                new Original(
                    rs.getString("mime_type"),
                    new BlobKey(rs.getString("bucket"), rs.getString("key"))))
        .optional();
  }

  public void setDocumentClass(DocumentId documentId, DocumentClass documentClass) {
    jdbc.sql("UPDATE document SET document_class = ?, updated_at = now() WHERE id = ?")
        .params(documentClass.name(), documentId.value())
        .update();
  }
}
