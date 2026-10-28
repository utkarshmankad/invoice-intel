package dev.invoiceintel.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import dev.invoiceintel.common.fixtures.InvoiceType;
import dev.invoiceintel.common.fixtures.SyntheticInvoiceGenerator;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** TXT-3 on synthetic scans: a rendered synthetic invoice, rotated and speckled. */
class ImagePreprocessorTest {

  private static BufferedImage page;

  private final ImagePreprocessor preprocessor = new ImagePreprocessor();

  @BeforeAll
  static void renderPage() {
    byte[] pdf =
        SyntheticInvoiceGenerator.toPdf(
            SyntheticInvoiceGenerator.generate(InvoiceType.GST_TAX_INVOICE, 5));
    var rendered = new AtomicReference<BufferedImage>();
    // 150 DPI keeps the tests fast; the thresholds still hold for invoice-sized text.
    new PageRenderer(150).render(pdf, (png, pageNo) -> rendered.set(read(png)));
    page = rendered.get();
  }

  @ParameterizedTest
  @ValueSource(doubles = {-6, -2.5, 0, 1.5, 4})
  void preprocess_skewedScan_isDeskewedWithinOneDegree(double degrees) {
    byte[] scan = scan(degrees);

    PreprocessedImage result = preprocessor.preprocess(scan);

    assertThat(result.skewDegrees()).isCloseTo(degrees, within(1.0));
    assertThat(preprocessor.estimateSkew(result.png())).isCloseTo(0, within(1.0));
  }

  @Test
  void preprocess_scan_yieldsBinaryImageOfSameSize() {
    PreprocessedImage result = preprocessor.preprocess(scan(3));

    BufferedImage out = read(result.png());
    assertThat(out.getWidth()).isEqualTo(page.getWidth());
    assertThat(out.getHeight()).isEqualTo(page.getHeight());
    var raster = out.getRaster();
    for (int y = 0; y < out.getHeight(); y += 7) {
      for (int x = 0; x < out.getWidth(); x += 7) {
        assertThat(raster.getSample(x, y, 0)).isIn(0, 255);
      }
    }
  }

  @Test
  void estimateSkew_beyondLimit_isTreatedAsLayout() {
    assertThat(preprocessor.estimateSkew(scan(30))).isZero();
  }

  @Test
  void estimateSkew_blankImage_isZero() {
    var blank = new BufferedImage(200, 300, BufferedImage.TYPE_BYTE_GRAY);
    var g = blank.createGraphics();
    g.setColor(Color.WHITE);
    g.fillRect(0, 0, 200, 300);
    g.dispose();

    assertThat(preprocessor.estimateSkew(png(blank))).isZero();
  }

  @Test
  void preprocess_notAnImage_throwsContentFreeException() {
    byte[] notImage = "not an image".getBytes(StandardCharsets.US_ASCII);

    assertThatThrownBy(() -> preprocessor.preprocess(notImage))
        .isInstanceOf(TextExtractionException.class)
        .hasMessage("could not decode page image");
  }

  @Test
  void preprocessedImage_copiesItsBytes() {
    byte[] bytes = {1, 2, 3};
    var image = new PreprocessedImage(bytes, 0);
    bytes[0] = 9;

    image.png()[1] = 9;

    assertThat(image.png()).containsExactly(1, 2, 3);
  }

  /** The page turned clockwise by {@code degrees} on a white sheet, with scanner speckles. */
  private static byte[] scan(double degrees) {
    var scan = new BufferedImage(page.getWidth(), page.getHeight(), BufferedImage.TYPE_INT_RGB);
    var g = scan.createGraphics();
    g.setColor(Color.WHITE);
    g.fillRect(0, 0, scan.getWidth(), scan.getHeight());
    g.setRenderingHint(
        RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    g.rotate(Math.toRadians(degrees), scan.getWidth() / 2.0, scan.getHeight() / 2.0);
    g.drawImage(page, 0, 0, null);
    g.dispose();
    var random = new Random(degrees == 0 ? 1 : Double.doubleToLongBits(degrees));
    for (int i = 0; i < 400; i++) {
      scan.setRGB(random.nextInt(scan.getWidth()), random.nextInt(scan.getHeight()), 0);
    }
    return png(scan);
  }

  private static byte[] png(BufferedImage image) {
    try {
      var out = new ByteArrayOutputStream();
      ImageIO.write(image, "png", out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static BufferedImage read(byte[] png) {
    try {
      return ImageIO.read(new ByteArrayInputStream(png));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
