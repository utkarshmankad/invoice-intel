package dev.invoiceintel.text;

import java.util.List;
import java.util.Objects;

/**
 * The words of a document with their boxes, from the PDF text layer or OCR (architecture.md §6.2).
 * Stored as {@code text_layer.layer} JSONB; it contains PII, so it is never logged or returned by
 * the API (PII-8).
 */
public record TextLayer(List<TextPage> pages) {

  public TextLayer {
    pages = List.copyOf(Objects.requireNonNull(pages, "pages"));
  }
}
