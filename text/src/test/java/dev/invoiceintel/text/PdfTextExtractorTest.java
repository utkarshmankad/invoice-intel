package dev.invoiceintel.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import dev.invoiceintel.common.fixtures.InvoiceType;
import dev.invoiceintel.common.fixtures.SyntheticInvoice;
import dev.invoiceintel.common.fixtures.SyntheticInvoiceGenerator;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Synthetic fixtures only (testing.md). */
class PdfTextExtractorTest {

  private final PdfTextExtractor extractor = new PdfTextExtractor();

  @ParameterizedTest
  @EnumSource(InvoiceType.class)
  void extract_syntheticInvoice_yieldsItsWordsOnOnePage(InvoiceType type) {
    SyntheticInvoice invoice = SyntheticInvoiceGenerator.generate(type, 42);

    TextLayer layer = extractor.extract(SyntheticInvoiceGenerator.toPdf(invoice));

    assertThat(layer.pages()).hasSize(1);
    TextPage page = layer.pages().getFirst();
    assertThat(page.pageNo()).isEqualTo(1);
    assertThat(page.source()).isEqualTo(TextSource.PDF_TEXT);
    assertThat(page.width()).isCloseTo(PDRectangle.A4.getWidth(), within(0.01));
    assertThat(page.height()).isCloseTo(PDRectangle.A4.getHeight(), within(0.01));
    List<String> texts = page.words().stream().map(Word::text).toList();
    assertThat(texts).contains(invoice.invoiceNumber());
    assertThat(texts).containsAll(Arrays.asList(invoice.amountInWords().split("\\s+")));
    assertThat(extractor.hasTextLayer(page)).isTrue();
  }

  @Test
  void extract_syntheticInvoice_boxesLieInsidePage() {
    byte[] pdf =
        SyntheticInvoiceGenerator.toPdf(
            SyntheticInvoiceGenerator.generate(InvoiceType.GST_TAX_INVOICE, 7));

    TextPage page = extractor.extract(pdf).pages().getFirst();

    assertThat(page.words())
        .allSatisfy(
            word -> {
              assertThat(word.x()).isBetween(0.0, page.width());
              assertThat(word.y()).isBetween(0.0, page.height());
              assertThat(word.w()).isPositive();
              assertThat(word.h()).isPositive();
              assertThat(word.x() + word.w()).isLessThanOrEqualTo(page.width());
              assertThat(word.y() + word.h()).isLessThanOrEqualTo(page.height());
              assertThat(word.confidence()).isEqualTo(1.0);
            });
  }

  @Test
  void extract_knownWord_hasBoxFromTopLeft() throws IOException {
    // Helvetica 20 pt, baseline 100 pt above the bottom edge.
    byte[] pdf = pdf("Total 1,180.00", 72, 100, 20);

    List<Word> words = extractor.extract(pdf).pages().getFirst().words();

    assertThat(words).extracting(Word::text).containsExactly("Total", "1,180.00");
    Word total = words.getFirst();
    double baselineFromTop = PDRectangle.A4.getHeight() - 100;
    assertThat(total.x()).isCloseTo(72, within(0.5));
    assertThat(total.y() + total.h()).isCloseTo(baselineFromTop, within(0.5));
    assertThat(total.h()).isBetween(5.0, 20.0);
    assertThat(words.get(1).x()).isGreaterThan(total.x() + total.w());
  }

  @Test
  void extract_pageWithoutText_hasNoTextLayer() throws IOException {
    TextPage page = extractor.extract(pdf(null, 0, 0, 0)).pages().getFirst();

    assertThat(page.words()).isEmpty();
    assertThat(extractor.hasTextLayer(page)).isFalse();
  }

  @Test
  void hasTextLayer_belowMinimum_isFalse() throws IOException {
    TextPage page = extractor.extract(pdf("short", 72, 100, 12)).pages().getFirst();

    assertThat(new PdfTextExtractor(5).hasTextLayer(page)).isTrue();
    assertThat(new PdfTextExtractor(6).hasTextLayer(page)).isFalse();
  }

  @Test
  void extract_notAPdf_throwsWithoutContent() {
    byte[] text = "not a pdf".getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() -> extractor.extract(text))
        .isInstanceOf(TextExtractionException.class)
        .hasMessage("could not read PDF text layer");
  }

  @Test
  void constructor_minimumBelowOne_isRejected() {
    assertThatThrownBy(() -> new PdfTextExtractor(0)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void records_rejectInvalidValues() {
    assertThatThrownBy(() -> new Word(" ", 0, 0, 1, 1, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TextPage(0, 1, 1, TextSource.OCR, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** One A4 page with {@code text} (or none) at {@code x}, baseline {@code y} from the bottom. */
  private static byte[] pdf(String text, float x, float y, float size) throws IOException {
    try (var document = new PDDocument()) {
      var page = new PDPage(PDRectangle.A4);
      document.addPage(page);
      if (text != null) {
        try (var content = new PDPageContentStream(document, page)) {
          content.beginText();
          content.setFont(new PDType1Font(FontName.HELVETICA), size);
          content.newLineAtOffset(x, y);
          content.showText(text);
          content.endText();
        }
      }
      var out = new ByteArrayOutputStream();
      document.save(out);
      return out.toByteArray();
    }
  }
}
