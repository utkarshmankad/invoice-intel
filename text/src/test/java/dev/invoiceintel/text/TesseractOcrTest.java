package dev.invoiceintel.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.invoiceintel.common.fixtures.InvoiceType;
import dev.invoiceintel.common.fixtures.SyntheticInvoiceGenerator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * OCR of rasterised synthetic fixtures (testing.md); needs Tesseract with {@code eng} installed, as
 * CI does (docs/ci.md).
 */
class TesseractOcrTest {

  private final TesseractOcr ocr = new TesseractOcr(tessdata(), List.of("eng"));

  @ParameterizedTest
  @EnumSource(InvoiceType.class)
  void ocr_rasterisedFixture_matchesAtLeast95PercentOfPdfWords(InvoiceType type) {
    byte[] pdf = SyntheticInvoiceGenerator.toPdf(SyntheticInvoiceGenerator.generate(type, 21));
    List<Word> expected = new PdfTextExtractor().extract(pdf).pages().getFirst().words();

    TextPage page = ocr.ocr(1, render(pdf));

    assertThat(page.source()).isEqualTo(TextSource.OCR);
    assertThat(page.pageNo()).isEqualTo(1);
    assertThat(wordMatch(expected, page.words())).isGreaterThanOrEqualTo(0.95);
  }

  @ParameterizedTest
  @EnumSource(InvoiceType.class)
  void ocr_rasterisedFixture_isClassifiedAsItsType(InvoiceType type) {
    // TXT-4 on scans: OCR splits and merges words differently from the PDF text layer.
    byte[] pdf = SyntheticInvoiceGenerator.toPdf(SyntheticInvoiceGenerator.generate(type, 33));

    TextLayer layer = new TextLayer(List.of(ocr.ocr(1, render(pdf))));

    assertThat(new DocumentClassifier().classify(layer).name())
        .isEqualTo(
            switch (type) {
              case GST_TAX_INVOICE -> "TAX_INVOICE";
              case CREDIT_NOTE -> "CREDIT_NOTE";
              case PRE_GST_INVOICE -> "PRE_GST_INVOICE";
            });
  }

  @Test
  void ocr_rasterisedFixture_boxesAndConfidencesAreInRange() {
    byte[] pdf =
        SyntheticInvoiceGenerator.toPdf(
            SyntheticInvoiceGenerator.generate(InvoiceType.GST_TAX_INVOICE, 3));

    TextPage page = ocr.ocr(2, render(pdf));

    assertThat(page.pageNo()).isEqualTo(2);
    // 300 DPI A4 in pixels.
    assertThat(page.width()).isEqualTo(2480);
    assertThat(page.height()).isEqualTo(3507);
    assertThat(page.words())
        .isNotEmpty()
        .allSatisfy(
            word -> {
              assertThat(word.x()).isBetween(0.0, page.width());
              assertThat(word.y()).isBetween(0.0, page.height());
              assertThat(word.x() + word.w()).isLessThanOrEqualTo(page.width());
              assertThat(word.y() + word.h()).isLessThanOrEqualTo(page.height());
              assertThat(word.confidence()).isBetween(0.0, 1.0);
            });
  }

  @Test
  void ocr_notAnImage_throwsContentFreeException() {
    byte[] notImage = "not an image".getBytes(StandardCharsets.US_ASCII);

    assertThatThrownBy(() -> ocr.ocr(1, notImage))
        .isInstanceOf(TextExtractionException.class)
        .hasMessage("could not decode page image");
  }

  @Test
  void ocr_missingLanguageData_throwsContentFreeException() {
    var broken = new TesseractOcr(Path.of("does-not-exist"), List.of("eng"));
    byte[] pdf =
        SyntheticInvoiceGenerator.toPdf(
            SyntheticInvoiceGenerator.generate(InvoiceType.CREDIT_NOTE, 4));

    assertThatThrownBy(() -> broken.ocr(1, render(pdf)))
        .isInstanceOf(TextExtractionException.class)
        .hasMessage("could not OCR page image");
  }

  @Test
  void constructor_noLanguages_isRejected() {
    assertThatThrownBy(() -> new TesseractOcr(Path.of("x"), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** {@code TESSDATA_PREFIX}, else where Homebrew (macOS) or apt (CI) installs language data. */
  private static Path tessdata() {
    Stream<Path> fromEnv =
        Optional.ofNullable(System.getenv("TESSDATA_PREFIX")).map(Path::of).stream();
    Stream<Path> known =
        Stream.of(
                "/opt/homebrew/share/tessdata",
                "/usr/local/share/tessdata",
                "/usr/share/tesseract-ocr/5/tessdata",
                "/usr/share/tesseract-ocr/4.00/tessdata")
            .map(Path::of);
    return Stream.concat(fromEnv, known)
        .filter(dir -> Files.isRegularFile(dir.resolve("eng.traineddata")))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Tesseract eng language data not found"));
  }

  /** Share of the PDF's words that OCR found, counting repeated words separately. */
  private static double wordMatch(List<Word> expected, List<Word> actual) {
    Map<String, Integer> found = new HashMap<>();
    actual.forEach(word -> found.merge(word.text(), 1, Integer::sum));
    int matched = 0;
    for (Word word : expected) {
      Integer left = found.get(word.text());
      if (left != null && left > 0) {
        found.put(word.text(), left - 1);
        matched++;
      }
    }
    return (double) matched / expected.size();
  }

  private static byte[] render(byte[] pdf) {
    var png = new AtomicReference<byte[]>();
    new PageRenderer().render(pdf, (image, pageNo) -> png.set(image));
    return png.get();
  }
}
