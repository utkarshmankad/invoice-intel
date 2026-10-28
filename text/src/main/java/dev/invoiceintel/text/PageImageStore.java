package dev.invoiceintel.text;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.common.logging.LogContext;
import dev.invoiceintel.storage.BlobKey;
import dev.invoiceintel.storage.BlobStore;
import dev.invoiceintel.text.internal.PageImageRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.ObjIntConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Renders a document's pages and stores them as {@code raw/{documentId}/pages/{n}.png} in the raw
 * bucket, each with a {@code PAGE_IMAGE} {@code blob_ref} row (TXT-2, architecture.md §6.2, §9.1).
 * The images contain PII and never leave the raw bucket.
 */
public final class PageImageStore {

  private static final Logger log = LoggerFactory.getLogger(PageImageStore.class);

  private final PageRenderer renderer;
  private final BlobStore blobStore;
  private final PageImageRepository pageImages;
  private final String rawBucket;

  public PageImageStore(
      PageRenderer renderer, BlobStore blobStore, JdbcClient jdbc, String rawBucket) {
    this.renderer = Objects.requireNonNull(renderer, "renderer");
    this.blobStore = Objects.requireNonNull(blobStore, "blobStore");
    this.pageImages = new PageImageRepository(jdbc);
    this.rawBucket = Objects.requireNonNull(rawBucket, "rawBucket");
  }

  /**
   * Renders and stores every page of {@code pdf}. Running it again for the same document replaces
   * the images under the same keys and keeps one row per page.
   *
   * @return the page image keys in page order
   * @throws TextExtractionException if the bytes are not a readable PDF
   */
  public List<BlobKey> renderAndStore(DocumentId documentId, byte[] pdf) {
    return renderAndStore(documentId, pdf, (png, pageNo) -> {});
  }

  /**
   * Like {@link #renderAndStore(DocumentId, byte[])}, and passes each stored PNG with its 1-based
   * page number to {@code pageSink} before the next page is rendered, so a caller can OCR it
   * without keeping more than one page on the heap (§10.1).
   */
  public List<BlobKey> renderAndStore(
      DocumentId documentId, byte[] pdf, ObjIntConsumer<byte[]> pageSink) {
    Objects.requireNonNull(documentId, "documentId");
    var keys = new ArrayList<BlobKey>();
    renderer.render(
        pdf,
        (png, pageNo) -> {
          keys.add(store(documentId, pageNo, png));
          pageSink.accept(png, pageNo);
        });
    try (var _ = LogContext.forDocument(documentId.value())) {
      log.atInfo()
          .addKeyValue("pages", keys.size())
          .addKeyValue("dpi", renderer.dpi())
          .log("page images stored");
    }
    return List.copyOf(keys);
  }

  /**
   * Stores an already encoded PNG as page {@code pageNo}, e.g. an uploaded photo used as-is
   * (architecture.md §6.2). Same key and row as a rendered page.
   */
  public BlobKey store(DocumentId documentId, int pageNo, byte[] png) {
    Objects.requireNonNull(documentId, "documentId");
    if (pageNo < 1) {
      throw new IllegalArgumentException("pageNo must be >= 1");
    }
    BlobKey key = keyFor(documentId, pageNo);
    // Blob first: a row must never point at a missing image.
    blobStore.put(key, png, "image/png");
    pageImages.upsert(documentId, pageNo, key);
    return key;
  }

  private BlobKey keyFor(DocumentId documentId, int pageNo) {
    return new BlobKey(rawBucket, "raw/" + documentId + "/pages/" + pageNo + ".png");
  }
}
