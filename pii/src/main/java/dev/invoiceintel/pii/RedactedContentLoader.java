package dev.invoiceintel.pii;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.pii.internal.PiiSpanRepository;
import dev.invoiceintel.pii.internal.RedactedBlobRepository;
import dev.invoiceintel.storage.BlobKey;
import dev.invoiceintel.storage.BlobStore;
import dev.invoiceintel.text.TextLayer;
import dev.invoiceintel.text.TextLayerStore;
import dev.invoiceintel.text.TextPage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Rebuilds the {@link RedactedContent} of a finished PII run for the Extract stage (architecture.md
 * §6.3, §6.4, ADR-17): the latest text layer with each stored {@code pii_span} replaced by its
 * token or marker, the same way {@link TextRedactor} built it, plus the masked page images ({@code
 * REDACTED_PAGE}). It reads no vault values, so the content holds no original PII.
 *
 * <p>Only runs that succeeded may be loaded: the caller passes the run number of the latest
 * succeeded PII run, so a document whose PII stage failed never yields a {@code RedactedContent}.
 */
public final class RedactedContentLoader {

  private final BlobStore blobStore;
  private final TextLayerStore layers;
  private final PiiSpanRepository spans;
  private final RedactedBlobRepository blobs;
  private final RedactionMode mode;

  /**
   * @param mode the configured redaction mode, which the PII runs were made with
   */
  public RedactedContentLoader(BlobStore blobStore, JdbcClient jdbc, RedactionMode mode) {
    this.blobStore = Objects.requireNonNull(blobStore, "blobStore");
    this.layers = new TextLayerStore(jdbc);
    this.spans = new PiiSpanRepository(jdbc);
    this.blobs = new RedactedBlobRepository(jdbc);
    this.mode = Objects.requireNonNull(mode, "mode");
  }

  /**
   * The redacted content of PII run {@code piiRunNo}, or empty if the text layer or a page's masked
   * image is missing.
   *
   * @throws IllegalArgumentException if a stored span doesn't fit the text layer
   */
  public Optional<RedactedContent> load(DocumentId documentId, int piiRunNo) {
    Objects.requireNonNull(documentId, "documentId");
    Optional<TextLayer> layer = layers.latest(documentId);
    if (layer.isEmpty()) {
      return Optional.empty();
    }
    Map<Integer, List<PiiSpanRepository.Row>> rowsByPage = new HashMap<>();
    for (PiiSpanRepository.Row row : spans.findByRun(documentId, piiRunNo)) {
      rowsByPage.computeIfAbsent(row.pageNo(), k -> new ArrayList<>()).add(row);
    }
    Map<Integer, BlobKey> images = blobs.pages(documentId);
    var pages = new ArrayList<RedactedContent.Page>(layer.get().pages().size());
    for (TextPage page : layer.get().pages()) {
      BlobKey key = images.get(page.pageNo());
      Optional<byte[]> png = key == null ? Optional.empty() : blobStore.get(key);
      if (png.isEmpty()) {
        return Optional.empty();
      }
      pages.add(page(page, rowsByPage.getOrDefault(page.pageNo(), List.of())).withImage(png.get()));
      rowsByPage.remove(page.pageNo());
    }
    if (!rowsByPage.isEmpty()) {
      throw new IllegalArgumentException("spans on pages not in the text layer");
    }
    return Optional.of(new RedactedContent(documentId, mode, pages));
  }

  private static RedactedContent.Page page(TextPage page, List<PiiSpanRepository.Row> rows) {
    PageText text = PageText.of(page);
    var pageSpans = new ArrayList<PiiSpan>(rows.size());
    var replacements = new ArrayList<String>(rows.size());
    for (PiiSpanRepository.Row row : rows) {
      if (row.end() > text.text().length()) {
        throw new IllegalArgumentException("span ends beyond page " + page.pageNo() + " text");
      }
      pageSpans.add(
          new PiiSpan(
              row.pageNo(), row.start(), row.end(), PiiType.valueOf(row.type()), row.detector()));
      replacements.add(row.token());
    }
    return TextRedactor.replaceWords(page, text, pageSpans, replacements);
  }
}
