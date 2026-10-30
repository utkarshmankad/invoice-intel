package dev.invoiceintel.pii.internal;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.storage.BlobKey;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@code REDACTED_PAGE} and {@code REDACTED_PDF} rows of {@code blob_ref} (architecture.md §7). */
public final class RedactedBlobRepository {

  private final JdbcClient jdbc;

  public RedactedBlobRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  /** Inserts the row, or only touches {@code updated_at} if the key is already referenced. */
  public void upsertPage(DocumentId documentId, int pageNo, BlobKey key) {
    upsert(documentId, "REDACTED_PAGE", pageNo, key);
  }

  /** Like {@link #upsertPage} for the whole-document PDF, which has no page number. */
  public void upsertPdf(DocumentId documentId, BlobKey key) {
    upsert(documentId, "REDACTED_PDF", null, key);
  }

  /** The masked page images ({@code REDACTED_PAGE}) by page number. */
  public Map<Integer, BlobKey> pages(DocumentId documentId) {
    record PageImage(int pageNo, BlobKey key) {}
    return jdbc
        .sql(
            "SELECT page_no, bucket, key FROM blob_ref"
                + " WHERE document_id = ? AND kind = 'REDACTED_PAGE'")
        .param(documentId.value())
        .query(
            (rs, row) ->
                new PageImage(
                    rs.getInt("page_no"), new BlobKey(rs.getString("bucket"), rs.getString("key"))))
        .stream()
        .collect(Collectors.toMap(PageImage::pageNo, PageImage::key));
  }

  private void upsert(DocumentId documentId, String kind, Integer pageNo, BlobKey key) {
    jdbc.sql(
            """
            INSERT INTO blob_ref (id, document_id, kind, bucket, key, page_no)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (bucket, key) DO UPDATE SET updated_at = now()
            """)
        .params(UUID.randomUUID(), documentId.value(), kind, key.bucket(), key.key(), pageNo)
        .update();
  }
}
