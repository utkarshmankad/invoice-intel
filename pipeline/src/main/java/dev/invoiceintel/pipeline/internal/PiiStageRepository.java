package dev.invoiceintel.pipeline.internal;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.storage.BlobKey;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The {@code blob_ref} reads of the PII stage. */
public final class PiiStageRepository {

  private final JdbcClient jdbc;

  public PiiStageRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  /** The stored page images ({@code PAGE_IMAGE}) by page number. */
  public Map<Integer, BlobKey> pageImages(DocumentId documentId) {
    record PageImage(int pageNo, BlobKey key) {}
    return jdbc
        .sql(
            "SELECT page_no, bucket, key FROM blob_ref WHERE document_id = ? AND kind = 'PAGE_IMAGE'")
        .param(documentId.value())
        .query(
            (rs, row) ->
                new PageImage(
                    rs.getInt("page_no"), new BlobKey(rs.getString("bucket"), rs.getString("key"))))
        .stream()
        .collect(Collectors.toMap(PageImage::pageNo, PageImage::key));
  }
}
