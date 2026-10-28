package dev.invoiceintel.text;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.function.ObjIntConsumer;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * Renders PDF pages to PNG for OCR, the vision strategy and the review UI (TXT-2, architecture.md
 * §6.2). Pages are rendered one at a time and handed to the caller before the next one starts, so
 * at most one page bitmap is on the heap per call (§10.1).
 */
public final class PageRenderer {

  /** Default resolution; matches {@code invoice-intel.text.dpi}. */
  public static final int DEFAULT_DPI = 300;

  private final int dpi;

  public PageRenderer() {
    this(DEFAULT_DPI);
  }

  public PageRenderer(int dpi) {
    if (dpi < 72 || dpi > 600) {
      throw new IllegalArgumentException("dpi must be between 72 and 600");
    }
    this.dpi = dpi;
  }

  public int dpi() {
    return dpi;
  }

  /**
   * Renders every page in order and passes each PNG with its 1-based page number to {@code
   * pageSink}.
   *
   * @return the number of pages
   * @throws TextExtractionException if the bytes are not a readable PDF or a page can't be rendered
   */
  public int render(byte[] pdf, ObjIntConsumer<byte[]> pageSink) {
    try (PDDocument document = Loader.loadPDF(pdf)) {
      var renderer = new PDFRenderer(document);
      int pages = document.getNumberOfPages();
      for (int pageNo = 1; pageNo <= pages; pageNo++) {
        pageSink.accept(png(renderer.renderImageWithDPI(pageNo - 1, dpi, ImageType.RGB)), pageNo);
      }
      return pages;
    } catch (IOException e) {
      throw new TextExtractionException("could not render PDF pages", e);
    }
  }

  private static byte[] png(BufferedImage image) throws IOException {
    var out = new ByteArrayOutputStream();
    try {
      ImageIO.write(image, "png", out);
    } finally {
      image.flush();
    }
    return out.toByteArray();
  }
}
