package dev.invoiceintel.pii;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

/**
 * Builds the redacted PDF as a <b>new</b> document from the masked page images (PII-4, ADR-05): one
 * image per page and nothing else, so no text operator, font, annotation, form field, attachment or
 * metadata of the original can survive. The PDF has no text layer by design (architecture.md §6.3).
 */
public final class RedactedPdf {

  private final int dpi;

  /**
   * @param dpi the resolution the page images were rendered at, which sets the PDF page size (e.g.
   *     {@code PageRenderer.DEFAULT_DPI})
   */
  public RedactedPdf(int dpi) {
    if (dpi < 72 || dpi > 600) {
      throw new IllegalArgumentException("dpi must be between 72 and 600");
    }
    this.dpi = dpi;
  }

  /**
   * The redacted PDF of {@code content}, one page per page in order.
   *
   * @throws IllegalArgumentException if a page has no masked image ({@link ImageRedactor} not run)
   */
  public byte[] build(RedactedContent content) {
    Objects.requireNonNull(content, "content");
    try (PDDocument document = new PDDocument();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      for (RedactedContent.Page page : content.pages()) {
        byte[] png =
            page.image()
                .orElseThrow(
                    () -> new IllegalArgumentException("page " + page.pageNo() + " has no image"));
        PDImageXObject image = PDImageXObject.createFromByteArray(document, png, null);
        float width = image.getWidth() * 72f / dpi;
        float height = image.getHeight() * 72f / dpi;
        var pdfPage = new PDPage(new PDRectangle(width, height));
        document.addPage(pdfPage);
        try (var stream = new PDPageContentStream(document, pdfPage)) {
          stream.drawImage(image, 0, 0, width, height);
        }
      }
      document.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException("could not build redacted PDF", e);
    }
  }
}
