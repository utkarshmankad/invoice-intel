package dev.invoiceintel.ingest.internal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.invoiceintel.common.fixtures.InvoiceType;
import dev.invoiceintel.common.fixtures.SyntheticInvoiceGenerator;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FileTypeDetectorTest {

  private final FileTypeDetector detector = new FileTypeDetector();

  @Test
  void detect_syntheticInvoicePdf_isPdf() {
    byte[] pdf =
        SyntheticInvoiceGenerator.toPdf(
            SyntheticInvoiceGenerator.generate(InvoiceType.GST_TAX_INVOICE, 1));

    assertThat(detector.detect(pdf)).isEqualTo("application/pdf");
  }

  @ParameterizedTest
  @CsvSource({"png, image/png", "jpg, image/jpeg"})
  void detect_image_isDetectedFromContent(String format, String expected) throws IOException {
    assertThat(detector.detect(image(format))).isEqualTo(expected);
  }

  @Test
  void detect_textNamedLikePdf_isNotPdf() {
    // Only content counts (ING-3); there is no file name to be fooled by.
    byte[] text = "not a pdf, despite any file name".getBytes(StandardCharsets.UTF_8);

    assertThat(detector.detect(text)).isNotEqualTo("application/pdf");
  }

  static byte[] image(String format) throws IOException {
    var out = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), format, out);
    return out.toByteArray();
  }
}
