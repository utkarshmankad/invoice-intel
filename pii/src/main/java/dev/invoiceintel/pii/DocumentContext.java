package dev.invoiceintel.pii;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.text.TextLayer;
import java.util.List;
import java.util.Objects;

/**
 * What detectors know about the document besides its text (architecture.md §6.3): its ID and the
 * parts of the page text that are seller data, which is not PII (specs.md §6.1).
 *
 * @param sellerRanges text of the seller block and seller ID lines, from {@link SellerBlockTagger}
 */
public record DocumentContext(DocumentId documentId, List<TextRange> sellerRanges) {

  public DocumentContext {
    Objects.requireNonNull(documentId, "documentId");
    sellerRanges = List.copyOf(Objects.requireNonNull(sellerRanges, "sellerRanges"));
  }

  /** A context without seller data, for callers that don't tag seller blocks. */
  public DocumentContext(DocumentId documentId) {
    this(documentId, List.of());
  }

  /** A context with the seller block of {@code layer} tagged. */
  public static DocumentContext of(DocumentId documentId, TextLayer layer) {
    return new DocumentContext(documentId, new SellerBlockTagger().tag(layer));
  }

  /**
   * Whether {@code span} is seller data and must be left alone: it lies entirely inside one seller
   * range. A span that also covers non-seller text stays, to keep recall (specs.md §6.3).
   */
  public boolean isSellerData(PiiSpan span) {
    return sellerRanges.stream().anyMatch(range -> range.contains(span));
  }
}
