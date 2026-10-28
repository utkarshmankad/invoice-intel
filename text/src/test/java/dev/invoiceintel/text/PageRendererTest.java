package dev.invoiceintel.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.invoiceintel.common.fixtures.InvoiceType;
import dev.invoiceintel.common.fixtures.SyntheticInvoiceGenerator;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Synthetic fixtures only (testing.md). */
class PageRendererTest {

  @Test
  void render_syntheticInvoiceAt300Dpi_yieldsOneA4SizedPng() throws IOException {
    byte[] pdf =
        SyntheticInvoiceGenerator.toPdf(
            SyntheticInvoiceGenerator.generate(InvoiceType.GST_TAX_INVOICE, 42));
    var pages = new ArrayList<Integer>();
    var images = new ArrayList<BufferedImage>();

    int count =
        new PageRenderer()
            .render(
                pdf,
                (png, pageNo) -> {
                  pages.add(pageNo);
                  images.add(read(png));
                });

    assertThat(count).isEqualTo(1);
    assertThat(pages).containsExactly(1);
    // A4 is 595.28 x 841.89 pt; PDFBox floors 300 DPI to 2480 x 3507 px.
    assertThat(images.getFirst().getWidth()).isEqualTo(2480);
    assertThat(images.getFirst().getHeight()).isEqualTo(3507);
  }

  @Test
  void render_multiPagePdf_passesPagesInOrderWithTheirSize() throws IOException {
    byte[] pdf = blankPdf(PDRectangle.A4, PDRectangle.LETTER, PDRectangle.A5);
    var pages = new ArrayList<Integer>();
    var widths = new ArrayList<Integer>();

    int count =
        new PageRenderer(72)
            .render(
                pdf,
                (png, pageNo) -> {
                  pages.add(pageNo);
                  widths.add(read(png).getWidth());
                });

    assertThat(count).isEqualTo(3);
    assertThat(pages).containsExactly(1, 2, 3);
    assertThat(widths).containsExactly(595, 612, 419);
  }

  @Test
  void render_notAPdf_throwsContentFreeException() {
    byte[] notPdf = "not a pdf".getBytes(StandardCharsets.US_ASCII);

    assertThatThrownBy(() -> new PageRenderer().render(notPdf, (png, pageNo) -> {}))
        .isInstanceOf(TextExtractionException.class)
        .hasMessage("could not render PDF pages");
  }

  @ParameterizedTest
  @ValueSource(ints = {71, 601})
  void constructor_dpiOutOfRange_isRejected(int dpi) {
    assertThatThrownBy(() -> new PageRenderer(dpi)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void dpi_default_is300() {
    assertThat(new PageRenderer().dpi()).isEqualTo(PageRenderer.DEFAULT_DPI).isEqualTo(300);
  }

  private static byte[] blankPdf(PDRectangle... sizes) throws IOException {
    try (var document = new PDDocument()) {
      for (PDRectangle size : sizes) {
        document.addPage(new PDPage(size));
      }
      var out = new ByteArrayOutputStream();
      document.save(out);
      return out.toByteArray();
    }
  }

  private static BufferedImage read(byte[] png) {
    try {
      return ImageIO.read(new ByteArrayInputStream(png));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
