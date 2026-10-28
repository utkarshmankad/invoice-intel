package dev.invoiceintel.pii;

import dev.invoiceintel.text.TextLayer;
import dev.invoiceintel.text.TextPage;
import dev.invoiceintel.text.Word;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.imageio.ImageIO;

/**
 * Masks PII on page images with solid rectangles over the span words' boxes (PII-5, architecture.md
 * §6.3). The boxes come from the text layer (PDF text positions or OCR word coordinates) and are
 * scaled from the page's unit to the image's pixels, so a page rendered at any resolution is masked
 * in the right place.
 *
 * <p>Every word a span touches is masked whole, padded on all sides: PDF word boxes run from the
 * glyph height above the baseline down to the baseline, so descenders and accents stick out of
 * them, and OCR boxes can be a pixel short. Errors name page numbers only (PII-8).
 */
public final class ImageRedactor {

  /** Padding above and below a word, in word heights (descenders, accents). */
  static final double VERTICAL_PADDING = 0.4;

  /** Padding left and right of a word, in word heights. */
  static final double HORIZONTAL_PADDING = 0.15;

  /** Minimum padding in image pixels, for OCR boxes that end a pixel early. */
  static final int MIN_PADDING_PX = 2;

  /**
   * Adds masked page images to a text redaction's content.
   *
   * @param redaction the {@link TextRedactor} result; its {@code spans} (seller spans already
   *     removed) are masked
   * @param layer the text layer the redaction was made from
   * @param pageImages encoded page images (PNG or JPEG) by 1-based page number; every page of the
   *     layer needs one, and it must be the image the page's word boxes were measured on (for an
   *     OCR page the preprocessed, deskewed image, not the stored {@code PAGE_IMAGE})
   * @return the same content with a masked PNG on every page
   * @throws IllegalArgumentException if a page has no image, or the layer doesn't match the content
   */
  public RedactedContent redact(
      TextRedactor.Result redaction, TextLayer layer, Map<Integer, byte[]> pageImages) {
    Objects.requireNonNull(redaction, "redaction");
    Objects.requireNonNull(layer, "layer");
    Objects.requireNonNull(pageImages, "pageImages");
    RedactedContent content = redaction.content();
    if (layer.pages().size() != content.pages().size()) {
      throw new IllegalArgumentException("layer and redacted content have different pages");
    }
    Map<Integer, List<PiiSpan>> byPage = new HashMap<>();
    for (PiiSpan span : redaction.spans()) {
      byPage.computeIfAbsent(span.pageNo(), k -> new ArrayList<>()).add(span);
    }
    var pages = new ArrayList<RedactedContent.Page>(content.pages().size());
    for (int i = 0; i < layer.pages().size(); i++) {
      TextPage page = layer.pages().get(i);
      RedactedContent.Page redacted = content.pages().get(i);
      if (redacted.pageNo() != page.pageNo()) {
        throw new IllegalArgumentException("layer and redacted content have different pages");
      }
      byte[] image = pageImages.get(page.pageNo());
      if (image == null) {
        throw new IllegalArgumentException("no image for page " + page.pageNo());
      }
      pages.add(
          redacted.withImage(mask(page, byPage.getOrDefault(page.pageNo(), List.of()), image)));
    }
    return content.withPages(pages);
  }

  /**
   * Masks the words of {@code spans} on one page image.
   *
   * @param spans spans on this page (from {@link TextRedactor.Result#spans()})
   * @param image the encoded page image (PNG or JPEG)
   * @return the masked image as PNG
   * @throws IllegalArgumentException if a span is on another page or ends beyond the page text, or
   *     the image can't be decoded
   */
  public byte[] mask(TextPage page, Collection<PiiSpan> spans, byte[] image) {
    Objects.requireNonNull(page, "page");
    Objects.requireNonNull(spans, "spans");
    Objects.requireNonNull(image, "image");
    BufferedImage source = decode(page.pageNo(), image);
    // Drawn onto plain RGB, so indexed or grey PNGs can't turn the mask into another colour.
    var masked =
        new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
    var graphics = masked.createGraphics();
    try {
      graphics.drawImage(source, 0, 0, null);
      graphics.setColor(Color.BLACK);
      double scaleX = page.width() > 0 ? source.getWidth() / page.width() : 1;
      double scaleY = page.height() > 0 ? source.getHeight() / page.height() : 1;
      PageText text = PageText.of(page);
      for (PiiSpan span : spans) {
        if (span.pageNo() != page.pageNo()) {
          throw new IllegalArgumentException("span on page " + span.pageNo() + " not this page");
        }
        if (span.end() > text.text().length()) {
          throw new IllegalArgumentException("span ends beyond page " + page.pageNo() + " text");
        }
        for (Word word : text.wordsIn(span.start(), span.end())) {
          fill(graphics, word, scaleX, scaleY);
        }
      }
    } finally {
      graphics.dispose();
      source.flush();
    }
    try {
      return png(masked);
    } finally {
      masked.flush();
    }
  }

  private static void fill(Graphics2D graphics, Word word, double scaleX, double scaleY) {
    double padX = Math.max(MIN_PADDING_PX, HORIZONTAL_PADDING * word.h() * scaleY);
    double padY = Math.max(MIN_PADDING_PX, VERTICAL_PADDING * word.h() * scaleY);
    int left = (int) Math.floor(word.x() * scaleX - padX);
    int top = (int) Math.floor(word.y() * scaleY - padY);
    int right = (int) Math.ceil((word.x() + word.w()) * scaleX + padX);
    int bottom = (int) Math.ceil((word.y() + word.h()) * scaleY + padY);
    graphics.fillRect(left, top, right - left, bottom - top);
  }

  private static BufferedImage decode(int pageNo, byte[] image) {
    try {
      BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(image));
      if (decoded == null) {
        throw new IllegalArgumentException("page " + pageNo + " image is not PNG or JPEG");
      }
      return decoded;
    } catch (IOException e) {
      throw new IllegalArgumentException("page " + pageNo + " image can't be read");
    }
  }

  private static byte[] png(BufferedImage image) {
    var out = new ByteArrayOutputStream();
    try {
      ImageIO.write(image, "png", out);
    } catch (IOException e) {
      throw new UncheckedIOException("could not encode masked page", e);
    }
    return out.toByteArray();
  }
}
