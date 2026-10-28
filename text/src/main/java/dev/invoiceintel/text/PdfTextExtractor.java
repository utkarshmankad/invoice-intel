package dev.invoiceintel.text;

import dev.invoiceintel.text.internal.WordCollector;
import java.io.IOException;
import java.util.ArrayList;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

/**
 * Reads the text layer of a digital PDF as words with boxes (TXT-1, TXT-2, architecture.md §6.2).
 * Pages without enough text are scans; the OCR path handles them (plan M1.8).
 */
public final class PdfTextExtractor {

  /** Default minimum of non-whitespace characters for a page to count as having a text layer. */
  public static final int DEFAULT_MIN_TEXT_CHARS = 20;

  private final int minTextChars;

  public PdfTextExtractor() {
    this(DEFAULT_MIN_TEXT_CHARS);
  }

  public PdfTextExtractor(int minTextChars) {
    if (minTextChars < 1) {
      throw new IllegalArgumentException("minTextChars must be >= 1");
    }
    this.minTextChars = minTextChars;
  }

  /**
   * The words of every page, in reading order.
   *
   * @throws TextExtractionException if the bytes are not a readable PDF
   */
  public TextLayer extract(byte[] pdf) {
    try (PDDocument document = Loader.loadPDF(pdf)) {
      var collector = new WordCollector();
      var pages = new ArrayList<TextPage>(document.getNumberOfPages());
      for (int pageNo = 1; pageNo <= document.getNumberOfPages(); pageNo++) {
        PDRectangle box = document.getPage(pageNo - 1).getCropBox();
        pages.add(
            new TextPage(
                pageNo,
                box.getWidth(),
                box.getHeight(),
                TextSource.PDF_TEXT,
                collector.words(document, pageNo)));
      }
      return new TextLayer(pages);
    } catch (IOException e) {
      throw new TextExtractionException("could not read PDF text layer", e);
    }
  }

  /** Whether the page has more than the minimum of text, so it doesn't need OCR (TXT-1). */
  public boolean hasTextLayer(TextPage page) {
    return page.textLength() >= minTextChars;
  }
}
