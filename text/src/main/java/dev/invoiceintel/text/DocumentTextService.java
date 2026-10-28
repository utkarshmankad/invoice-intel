package dev.invoiceintel.text;

import dev.invoiceintel.common.DocumentId;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import javax.imageio.ImageIO;

/**
 * The text work of the Text stage (TXT-1..3, architecture.md §6.2): stores page images and returns
 * the document's {@link TextLayer}. PDF pages keep their text layer; pages without one, and
 * uploaded photos, are preprocessed and OCR'd. At most {@code maxConcurrentDocuments} documents are
 * rendered or OCR'd at once to keep the heap inside the memory budget (§10.1).
 */
public final class DocumentTextService {

  public static final String PDF = "application/pdf";
  public static final String JPEG = "image/jpeg";
  public static final String PNG = "image/png";

  private final PdfTextExtractor pdfText;
  private final PageImageStore pageImages;
  private final TesseractOcr ocr;
  private final Semaphore permits;
  private volatile ImagePreprocessor preprocessor;

  public DocumentTextService(
      PdfTextExtractor pdfText,
      PageImageStore pageImages,
      TesseractOcr ocr,
      int maxConcurrentDocuments) {
    this.pdfText = Objects.requireNonNull(pdfText, "pdfText");
    this.pageImages = Objects.requireNonNull(pageImages, "pageImages");
    this.ocr = Objects.requireNonNull(ocr, "ocr");
    if (maxConcurrentDocuments < 1) {
      throw new IllegalArgumentException("maxConcurrentDocuments must be >= 1");
    }
    this.permits = new Semaphore(maxConcurrentDocuments, true);
  }

  /**
   * Text of one document with the given (Tika-detected) MIME type.
   *
   * @throws TextExtractionException if the content can't be read or OCR fails
   */
  public TextLayer extract(DocumentId documentId, String mimeType, byte[] content) {
    Objects.requireNonNull(documentId, "documentId");
    try {
      permits.acquire();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new TextExtractionException("interrupted while waiting for a text slot", e);
    }
    try {
      return switch (mimeType) {
        case PDF -> fromPdf(documentId, content);
        case JPEG, PNG -> fromImage(documentId, content);
        default -> throw new TextExtractionException("unsupported document type", null);
      };
    } finally {
      permits.release();
    }
  }

  private TextLayer fromPdf(DocumentId documentId, byte[] pdf) {
    List<TextPage> pages = new ArrayList<>(pdfText.extract(pdf).pages());
    pageImages.renderAndStore(
        documentId,
        pdf,
        (png, pageNo) -> {
          if (!pdfText.hasTextLayer(pages.get(pageNo - 1))) {
            pages.set(pageNo - 1, ocrPage(pageNo, png));
          }
        });
    return new TextLayer(pages);
  }

  // Photos are used as-is (§6.2): stored as page 1, normalised to PNG.
  private TextLayer fromImage(DocumentId documentId, byte[] image) {
    byte[] png = toPng(image);
    pageImages.store(documentId, 1, png);
    return new TextLayer(List.of(ocrPage(1, png)));
  }

  // Boxes are in the pixels of the preprocessed (deskewed) image.
  private TextPage ocrPage(int pageNo, byte[] png) {
    return ocr.ocr(pageNo, preprocessor().preprocess(png).png());
  }

  // OpenCV's native library loads on first use, so digital PDFs never need it.
  private ImagePreprocessor preprocessor() {
    ImagePreprocessor current = preprocessor;
    if (current == null) {
      synchronized (this) {
        current = preprocessor;
        if (current == null) {
          current = new ImagePreprocessor();
          preprocessor = current;
        }
      }
    }
    return current;
  }

  private static byte[] toPng(byte[] image) {
    try {
      BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(image));
      if (decoded == null) {
        throw new TextExtractionException("could not decode page image", null);
      }
      var out = new ByteArrayOutputStream();
      try {
        ImageIO.write(decoded, "png", out);
      } finally {
        decoded.flush();
      }
      return out.toByteArray();
    } catch (IOException e) {
      throw new TextExtractionException("could not decode page image", e);
    }
  }
}
