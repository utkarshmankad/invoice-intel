package dev.invoiceintel.text.internal;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.storage.BlobKey;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@code PAGE_IMAGE} rows of {@code blob_ref} (architecture.md §7). */
public final class PageImageRepository {

  private final JdbcClient jdbc;

  public PageImageRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  /** Inserts the row, or only touches {@code updated_at} if the key is already referenced. */
  public void upsert(DocumentId documentId, int pageNo, BlobKey key) {
    jdbc.sql(
            """
            INSERT INTO blob_ref (id, document_id, kind, bucket, key, page_no)
            VALUES (?, ?, 'PAGE_IMAGE', ?, ?, ?)
            ON CONFLICT (bucket, key) DO UPDATE SET updated_at = now()
            """)
        .params(UUID.randomUUID(), documentId.value(), key.bucket(), key.key(), pageNo)
        .update();
  }
}
