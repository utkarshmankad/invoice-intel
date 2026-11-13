package dev.invoiceintel.pii;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.pii.internal.RedactedBlobRepository;
import dev.invoiceintel.storage.BlobKey;
import dev.invoiceintel.storage.BlobStore;
import dev.invoiceintel.text.PageSize;
import dev.invoiceintel.text.TextLayerStore;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Read-only view of a document's masked page images for the review UI (REV-2, architecture.md §6.8,
 * ADR-29). Only {@code REDACTED_PAGE} blobs are ever returned, never page renders or originals, so
 * the UI can't show an unredacted image. {@code REDACTED_PAGE} rows exist only once a PII run
 * committed (a failed run rolls them back). Page sizes come from the latest text layer without its
 * words, so this reads no document text and no vault values.
 */
public final class RedactedPageReader {

  private final BlobStore blobStore;
  private final TextLayerStore layers;
  private final RedactedBlobRepository blobs;

  public RedactedPageReader(BlobStore blobStore, JdbcClient jdbc) {
    this.blobStore = Objects.requireNonNull(blobStore, "blobStore");
    this.layers = new TextLayerStore(jdbc);
    this.blobs = new RedactedBlobRepository(jdbc);
  }

  /**
   * The document's pages that have a masked image, by page number, with the size of the text
   * layer's page: the unit of evidence boxes. Empty before the PII stage first succeeded.
   */
  public List<RedactedPage> pages(DocumentId documentId) {
    Objects.requireNonNull(documentId, "documentId");
    Map<Integer, BlobKey> images = blobs.pages(documentId);
    return layers.pageSizes(documentId).stream()
        .filter(size -> images.containsKey(size.pageNo()))
        .map(RedactedPageReader::page)
        .toList();
  }

  /** The masked PNG of page {@code pageNo}, or empty if the page has none. */
  public Optional<byte[]> image(DocumentId documentId, int pageNo) {
    Objects.requireNonNull(documentId, "documentId");
    BlobKey key = blobs.pages(documentId).get(pageNo);
    return key == null ? Optional.empty() : blobStore.get(key);
  }

  private static RedactedPage page(PageSize size) {
    return new RedactedPage(size.pageNo(), size.width(), size.height());
  }

  /**
   * A page with a masked image.
   *
   * @param width the text layer page's width, in the unit of its word and evidence boxes
   * @param height the text layer page's height, in the same unit
   */
  public record RedactedPage(int pageNo, double width, double height) {}
}
