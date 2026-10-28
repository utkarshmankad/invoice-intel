package dev.invoiceintel.ingest.internal;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.ingest.DocumentStatus;
import dev.invoiceintel.storage.BlobKey;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The {@code document} and {@code blob_ref} rows written at upload (architecture.md §7). */
public final class DocumentRepository {

  private final JdbcClient jdbc;

  public DocumentRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  public Optional<DocumentId> findIdBySha256(String sha256) {
    return jdbc.sql("SELECT id FROM document WHERE sha256 = ?")
        .param(sha256)
        .query(UUID.class)
        .optional()
        .map(DocumentId::new);
  }

  public Optional<String> findStatus(DocumentId id) {
    return jdbc.sql("SELECT status FROM document WHERE id = ?")
        .param(id.value())
        .query(String.class)
        .optional();
  }

  /** Newest first, at most {@code limit}; {@code status} null means any status. */
  public List<DocumentStatus> list(String status, int limit) {
    return jdbc.sql(
            """
            SELECT id, status FROM document
            WHERE CAST(? AS VARCHAR) IS NULL OR status = ?
            ORDER BY created_at DESC, id
            LIMIT ?
            """)
        .params(status, status, limit)
        .query(
            (rs, row) ->
                new DocumentStatus(
                    new DocumentId(rs.getObject("id", UUID.class)), rs.getString("status")))
        .list();
  }

  /**
   * Inserts the document in {@code RECEIVED} with its ORIGINAL blob reference, in one statement so
   * both rows exist or neither does.
   *
   * @return false if a document with the same SHA-256 already exists
   */
  public boolean insertReceived(DocumentId id, String sha256, String mimeType, BlobKey original) {
    return jdbc.sql(
                """
                WITH inserted AS (
                    INSERT INTO document (id, sha256, mime_type, status)
                    VALUES (?, ?, ?, 'RECEIVED')
                    ON CONFLICT (sha256) DO NOTHING
                    RETURNING id)
                INSERT INTO blob_ref (id, document_id, kind, bucket, key)
                SELECT ?, id, 'ORIGINAL', ?, ? FROM inserted
                """)
            .params(
                id.value(), sha256, mimeType, UUID.randomUUID(), original.bucket(), original.key())
            .update()
        == 1;
  }
}
