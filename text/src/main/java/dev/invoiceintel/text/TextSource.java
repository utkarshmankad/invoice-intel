package dev.invoiceintel.text;

/** Where a page's words came from (architecture.md §6.2). */
public enum TextSource {
  /** The PDF's own text layer (TXT-1). */
  PDF_TEXT,
  /** Tesseract OCR of the rendered page (TXT-2). */
  OCR
}
