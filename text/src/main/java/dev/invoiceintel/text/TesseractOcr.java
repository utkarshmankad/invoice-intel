package dev.invoiceintel.text;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.imageio.ImageIO;
import net.sourceforge.tess4j.ITessAPI.TessPageIteratorLevel;
import net.sourceforge.tess4j.Tesseract;

/**
 * OCR of one page image with Tess4J into the common {@link TextPage} format (TXT-1, TXT-2,
 * architecture.md §6.2). Boxes and the page size are in image pixels; confidences are scaled to
 * 0–1. Tesseract instances aren't thread-safe, so each call creates its own; callers cap
 * concurrency (§10.1).
 */
public final class TesseractOcr {

  private final Path tessdata;
  private final String languages;

  /**
   * @param tessdata folder holding the {@code *.traineddata} files
   * @param languages Tesseract language codes, e.g. {@code eng}, {@code hin}
   */
  public TesseractOcr(Path tessdata, List<String> languages) {
    this.tessdata = Objects.requireNonNull(tessdata, "tessdata");
    if (languages.isEmpty()) {
      throw new IllegalArgumentException("languages must not be empty");
    }
    this.languages = String.join("+", languages);
  }

  /**
   * Words of an encoded page image (PNG or JPEG) with pixel boxes.
   *
   * @throws TextExtractionException if the image can't be decoded or OCR fails
   */
  public TextPage ocr(int pageNo, byte[] image) {
    BufferedImage decoded = decode(image);
    var tesseract = new Tesseract();
    tesseract.setDatapath(tessdata.toString());
    tesseract.setLanguage(languages);
    var words = new ArrayList<Word>();
    try {
      for (var word : tesseract.getWords(decoded, TessPageIteratorLevel.RIL_WORD)) {
        String text = word.getText().strip();
        if (text.isEmpty()) {
          continue;
        }
        var box = word.getBoundingBox();
        words.add(
            new Word(
                text,
                box.x,
                box.y,
                box.width,
                box.height,
                Math.clamp(word.getConfidence() / 100.0, 0.0, 1.0)));
      }
    } catch (RuntimeException | LinkageError e) {
      // A missing native library surfaces as a LinkageError; bad language data as an exception.
      throw new TextExtractionException("could not OCR page image", e);
    } finally {
      decoded.flush();
    }
    return new TextPage(pageNo, decoded.getWidth(), decoded.getHeight(), TextSource.OCR, words);
  }

  private static BufferedImage decode(byte[] image) {
    try {
      BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(image));
      if (decoded == null) {
        throw new TextExtractionException("could not decode page image", null);
      }
      return decoded;
    } catch (IOException e) {
      throw new TextExtractionException("could not decode page image", e);
    }
  }
}
