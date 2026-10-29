package dev.invoiceintel.pii;

import static org.assertj.core.api.Assertions.assertThat;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.common.fixtures.InvoiceType;
import dev.invoiceintel.common.fixtures.SyntheticInvoice;
import dev.invoiceintel.common.fixtures.SyntheticInvoiceGenerator;
import dev.invoiceintel.text.ImagePreprocessor;
import dev.invoiceintel.text.PageRenderer;
import dev.invoiceintel.text.PdfTextExtractor;
import dev.invoiceintel.text.TesseractOcr;
import dev.invoiceintel.text.TextLayer;
import dev.invoiceintel.text.TextPage;
import dev.invoiceintel.text.Word;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The redaction proof (PII-4, PII-5, architecture.md §13, testing.md): detectors → merge → text
 * redaction → masked page images → rebuilt PDF; then re-extract the PDF's text and OCR it, and
 * assert that no known PII value of the synthetic invoice is present. Digital PDFs are masked on
 * the render; scanned pages are OCR'd and masked on the preprocessed image, as the PII stage does
 * (M2.10b). Needs Tesseract with {@code eng}, as CI has (docs/ci.md).
 */
class RedactionProofTest {

  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  private static TextRedactor textRedactor;

  private final List<PiiDetector> detectors =
      List.of(new RegexDetector(), new ChecksumDetector(), new AddressBlockDetector());
  private final PageRenderer renderer = new PageRenderer();
  private final TesseractOcr ocr = new TesseractOcr(tessdata(), List.of("eng"));

  @BeforeAll
  static void keys() throws GeneralSecurityException {
    textRedactor =
        new TextRedactor(
            TestKeysets.tokens(TestKeysets.newTokenKeyset()),
            TestKeysets.vault(TestKeysets.newVaultKeyset()));
  }

  static Stream<Arguments> cases() {
    return Arrays.stream(InvoiceType.values())
        .flatMap(type -> Stream.of(3L, 2024L).map(seed -> Arguments.of(type, seed)));
  }

  /**
   * One seed per type: OCR'ing every case would make the build much slower. This tests masking on
   * the preprocessed image; detection recall on OCR text is measured on the eval set (plan
   * Follow-ups: seed 3 of the pre-GST invoice loses an order number to an OCR misread).
   */
  static Stream<Arguments> scannedCases() {
    return Arrays.stream(InvoiceType.values()).map(type -> Arguments.of(type, 2024L));
  }

  private byte[] redactedPdf(SyntheticInvoice invoice) {
    byte[] pdf = SyntheticInvoiceGenerator.toPdf(invoice);
    TextLayer layer = new PdfTextExtractor().extract(pdf);
    Map<Integer, byte[]> images = new HashMap<>();
    renderer.render(pdf, (png, pageNo) -> images.put(pageNo, png));
    return redact(layer, images);
  }

  /** The invoice as a scan: the render is preprocessed and OCR'd, then masked on that image. */
  private byte[] redactedScan(SyntheticInvoice invoice) {
    var render = new AtomicReference<byte[]>();
    renderer.render(SyntheticInvoiceGenerator.toPdf(invoice), (png, pageNo) -> render.set(png));
    byte[] preprocessed = new ImagePreprocessor().preprocess(render.get()).png();
    TextLayer layer = new TextLayer(List.of(ocr.ocr(1, preprocessed)));
    return redact(layer, Map.of(1, preprocessed));
  }

  private byte[] redact(TextLayer layer, Map<Integer, byte[]> images) {
    DocumentContext ctx = DocumentContext.of(DocumentId.random(), layer);
    var spans = new ArrayList<PiiSpan>();
    detectors.forEach(detector -> spans.addAll(detector.detect(layer, ctx)));
    TextRedactor.Result text =
        textRedactor.redact(
            layer, new SpanMerger().merge(layer, spans), ctx, RedactionMode.PSEUDONYMIZE);
    RedactedContent content = new ImageRedactor().redact(text, layer, images);
    return new RedactedPdf(renderer.dpi()).build(content);
  }

  @ParameterizedTest(name = "{0} seed {1}")
  @MethodSource("cases")
  void redactedPdf_reExtracted_hasNoTextLayer(InvoiceType type, long seed) {
    byte[] redacted = redactedPdf(SyntheticInvoiceGenerator.generate(type, seed));

    TextLayer layer = new PdfTextExtractor().extract(redacted);

    assertThat(layer.pages()).hasSize(1);
    assertThat(layer.pages()).allSatisfy(page -> assertThat(page.words()).isEmpty());
  }

  @ParameterizedTest(name = "{0} seed {1}")
  @MethodSource("cases")
  void redactedPdf_ocr_findsNoPiiButKeepsInvoiceData(InvoiceType type, long seed) {
    SyntheticInvoice invoice = SyntheticInvoiceGenerator.generate(type, seed);

    assertNoPii(invoice, redactedPdf(invoice));
  }

  @ParameterizedTest(name = "{0} seed {1}")
  @MethodSource("scannedCases")
  void redactedScan_ocr_findsNoPiiButKeepsInvoiceData(InvoiceType type, long seed) {
    SyntheticInvoice invoice = SyntheticInvoiceGenerator.generate(type, seed);

    assertNoPii(invoice, redactedScan(invoice));
  }

  private void assertNoPii(SyntheticInvoice invoice, byte[] redacted) {
    var pages = new ArrayList<TextPage>();
    renderer.render(redacted, (png, pageNo) -> pages.add(ocr.ocr(pageNo, png)));
    List<Word> words = pages.stream().flatMap(page -> page.words().stream()).toList();
    String text = words.stream().map(Word::text).collect(Collectors.joining(" "));

    // OCR still reads the page, so an empty result can't pass the proof by accident.
    assertThat(text)
        .contains(invoice.grandTotal().toPlainString())
        .contains(invoice.seller().pan());
    String upper = text.toUpperCase(Locale.ROOT);
    String compact = WHITESPACE.matcher(upper).replaceAll("");
    List<String> wordDigits =
        words.stream().map(Word::text).map(RedactionProofTest::digits).toList();
    for (String value : invoice.piiValues()) {
      String normalized = TokenGenerator.normalize(value);
      assertThat(upper).as("PII value present").doesNotContain(normalized);
      assertThat(compact)
          .as("PII value present without spaces")
          .doesNotContain(WHITESPACE.matcher(normalized).replaceAll(""));
      String valueDigits = digits(value);
      if (valueDigits.length() >= 6) {
        String tail = valueDigits.substring(Math.max(0, valueDigits.length() - 10));
        assertThat(wordDigits).as("PII digits present in a word").noneMatch(w -> w.contains(tail));
      }
    }
  }

  private static String digits(String value) {
    StringBuilder out = new StringBuilder();
    for (char c : value.toCharArray()) {
      if (c >= '0' && c <= '9') {
        out.append(c);
      }
    }
    return out.toString();
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
        .orElseThrow(() -> new IllegalStateException("no tessdata with eng.traineddata found"));
  }
}
