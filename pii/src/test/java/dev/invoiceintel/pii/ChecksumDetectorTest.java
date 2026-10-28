package dev.invoiceintel.pii;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.common.checksum.GstinChecksum;
import dev.invoiceintel.common.checksum.Verhoeff;
import dev.invoiceintel.common.fixtures.FakeIds;
import dev.invoiceintel.common.fixtures.GstState;
import dev.invoiceintel.common.fixtures.InvoiceType;
import dev.invoiceintel.common.fixtures.SyntheticInvoice;
import dev.invoiceintel.common.fixtures.SyntheticInvoiceGenerator;
import dev.invoiceintel.text.PdfTextExtractor;
import dev.invoiceintel.text.TextLayer;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Synthetic, generated identifiers only (testing.md). Aadhaar-shaped values are built at run time
 * so the CI secret scan (.gitleaks.toml) never sees one in source.
 */
class ChecksumDetectorTest {

  private static final DocumentContext CTX = new DocumentContext(DocumentId.random());
  private static final String DIGITS = "0123456789";

  private final ChecksumDetector detector = new ChecksumDetector();

  private static String gstin(long seed) {
    var random = new Random(seed);
    GstState state = GstState.values()[random.nextInt(GstState.values().length)];
    return FakeIds.gstin(state, FakeIds.pan(random, 'C', 'S'), '1');
  }

  /** Same value with a different, wrong check character. */
  private static String withWrongCheck(String value, String alphabet) {
    char last = value.charAt(value.length() - 1);
    char wrong = alphabet.charAt((alphabet.indexOf(last) + 1) % alphabet.length());
    return value.substring(0, value.length() - 1) + wrong;
  }

  private static String grouped(String aadhaar, String separator) {
    return aadhaar.substring(0, 4)
        + separator
        + aadhaar.substring(4, 8)
        + separator
        + aadhaar.substring(8);
  }

  // --- GSTIN ---

  @ParameterizedTest(name = "seed {0}")
  @ValueSource(longs = {1, 2, 3, 42, 99})
  void detect_validGstin_isFoundByChecksum(long seed) {
    String gstin = gstin(seed);

    assertThat(spans("Buyer GSTIN: " + gstin + " Place of supply"))
        .containsExactly(tuple(gstin, PiiType.GSTIN, ChecksumDetector.GSTIN_RULE));
  }

  @Test
  void detect_validGstinWithoutLabel_isFound() {
    String gstin = gstin(7);

    assertThat(spans("Billing " + gstin + ","))
        .containsExactly(tuple(gstin, PiiType.GSTIN, ChecksumDetector.GSTIN_RULE));
  }

  @Test
  void detect_gstinShapeWithWrongCheckAndNoLabel_findsNothing() {
    String bad = withWrongCheck(gstin(7), "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ");

    assertThat(spans("Ref " + bad + " shipped")).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "GSTIN: {V}",
        "GSTIN/UIN : {V}",
        "GST No. {V}",
        "gst registration no: {V}",
        "Seller GSTIN - {V} State"
      })
  void detect_labelledGstinWithWrongCheck_isFoundByLabel(String template) {
    String bad = withWrongCheck(gstin(11), "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ");

    assertThat(spans(template.replace("{V}", bad)))
        .containsExactly(tuple(bad, PiiType.GSTIN, ChecksumDetector.GSTIN_LABEL_RULE));
  }

  @Test
  void detect_labelledGstinWithOcrConfusion_isFoundByLabel() {
    // A digit misread as the letter O breaks the layout as well as the checksum.
    String misread = "O" + gstin(13).substring(1);

    assertThat(spans("GSTIN " + misread))
        .containsExactly(tuple(misread, PiiType.GSTIN, ChecksumDetector.GSTIN_LABEL_RULE));
  }

  @Test
  void detect_labelledValidGstin_isReportedOnce() {
    String gstin = gstin(17);

    assertThat(spans("GSTIN/UIN: " + gstin))
        .containsExactly(tuple(gstin, PiiType.GSTIN, ChecksumDetector.GSTIN_RULE));
  }

  @ParameterizedTest(name = "nothing in: {0}")
  @ValueSource(
      strings = {
        "CGST 9% SGST 9% IGST 18%",
        "GST 18% on ABCDEFGHIJKLMNO",
        "GST Amount 123.45 Invoice INV1234567890123",
        "GST Registration No: pending",
        "GSTIN not applicable",
        "HSN 847130 GST 18.00",
        "GST 12 ABCD12345678901 later"
      })
  void detect_gstWordsWithoutGstin_findNothing(String text) {
    assertThat(spans(text)).isEmpty();
  }

  @Test
  void detect_gstinInsideLongerCode_findsNothing() {
    String gstin = gstin(5);

    assertThat(spans("Code X" + gstin + " and " + gstin + "9")).isEmpty();
  }

  static Stream<String> generatedGstins() {
    return IntStream.range(0, 200).mapToObj(i -> gstin(1000L + i));
  }

  @ParameterizedTest
  @MethodSource("generatedGstins")
  void detect_generatedGstin_foundAndWrongCheckNot(String gstin) {
    assertThat(spans("Ship to " + gstin)).hasSize(1);
    for (char c : "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray()) {
      if (c != gstin.charAt(14)) {
        assertThat(spans("Ship to " + gstin.substring(0, 14) + c)).isEmpty();
      }
    }
  }

  // --- Aadhaar ---

  static Stream<String> generatedAadhaars() {
    var random = new Random(20261028L);
    return IntStream.range(0, 200).mapToObj(i -> FakeIds.aadhaar(random));
  }

  @ParameterizedTest
  @MethodSource("generatedAadhaars")
  void detect_validAadhaar_isFoundInEachLayout(String aadhaar) {
    for (String value : List.of(aadhaar, grouped(aadhaar, " "), grouped(aadhaar, "-"))) {
      assertThat(spans("Aadhaar: " + value + " issued"))
          .containsExactly(tuple(value, PiiType.AADHAAR, ChecksumDetector.AADHAAR_RULE));
    }
  }

  @ParameterizedTest
  @MethodSource("generatedAadhaars")
  void detect_aadhaarWithWrongCheckDigit_findsNothing(String aadhaar) {
    for (char d : DIGITS.toCharArray()) {
      if (d != aadhaar.charAt(11)) {
        String bad = aadhaar.substring(0, 11) + d;
        assertThat(spans("UID " + bad)).isEmpty();
        assertThat(spans("UID " + grouped(bad, " "))).isEmpty();
      }
    }
  }

  @Test
  void detect_aadhaarShapeStartingWithZeroOrOne_findsNothing() {
    String aadhaar = FakeIds.aadhaar(new Random(3));
    String body = aadhaar.substring(1, 11);

    // Fix the check digit so only the first digit rules the value out.
    for (String first : List.of("0", "1")) {
      String candidate = first + body + Verhoeff.checkDigit(first + body);
      assertThat(spans("No " + candidate)).isEmpty();
    }
  }

  @Test
  void detect_aadhaarInsideLongerNumberOrDecimal_findsNothing() {
    String aadhaar = FakeIds.aadhaar(new Random(9));

    assertThat(spans("Amount 1" + aadhaar)).isEmpty();
    assertThat(spans("Amount " + aadhaar + "7")).isEmpty();
    assertThat(spans("Amount " + aadhaar + ".50")).isEmpty();
    assertThat(spans("Amount 0." + aadhaar)).isEmpty();
    assertThat(spans("Ref A" + aadhaar)).isEmpty();
  }

  // --- Pages and fixtures ---

  @Test
  void detect_severalPages_reportsEachPage() {
    String gstin = gstin(21);
    String aadhaar = FakeIds.aadhaar(new Random(21));
    var layer =
        new TextLayer(
            List.of(
                RegexDetectorTest.page(1, "GSTIN " + gstin),
                RegexDetectorTest.page(2, "nothing here"),
                RegexDetectorTest.page(3, "Aadhaar " + aadhaar)));

    assertThat(detector.detect(layer, CTX))
        .extracting(PiiSpan::pageNo, PiiSpan::type)
        .containsExactly(tuple(1, PiiType.GSTIN), tuple(3, PiiType.AADHAAR));
  }

  static Stream<Arguments> fixtures() {
    return Arrays.stream(InvoiceType.values())
        .flatMap(type -> Stream.of(3L, 17L, 99L).map(seed -> Arguments.of(type, seed)));
  }

  @ParameterizedTest(name = "{0} seed {1}")
  @MethodSource("fixtures")
  void detect_syntheticInvoice_findsOnlyTheGstin(InvoiceType type, long seed) {
    SyntheticInvoice invoice = SyntheticInvoiceGenerator.generate(type, seed);
    TextLayer layer = new PdfTextExtractor().extract(SyntheticInvoiceGenerator.toPdf(invoice));
    String text = PageText.of(layer.pages().getFirst()).text();

    List<String> found =
        detector.detect(layer, CTX).stream()
            .map(span -> text.substring(span.start(), span.end()))
            .toList();

    if (type == InvoiceType.PRE_GST_INVOICE) {
      assertThat(found).isEmpty();
    } else {
      // The seller's GSTIN; seller-block tagging (M2.3) decides it is not PII.
      assertThat(found).containsOnly(invoice.seller().taxId());
      assertThat(GstinChecksum.isValid(invoice.seller().taxId())).isTrue();
    }
  }

  @Test
  void detect_noPages_findsNothing() {
    assertThat(detector.detect(new TextLayer(List.of()), CTX)).isEmpty();
  }

  /** (value, type, rule) of each span found in a one-page layer of {@code text}. */
  private List<Tuple> spans(String text) {
    TextLayer layer = new TextLayer(List.of(RegexDetectorTest.page(1, text)));
    String pageText = PageText.of(layer.pages().getFirst()).text();
    return detector.detect(layer, CTX).stream()
        .map(
            span ->
                tuple(pageText.substring(span.start(), span.end()), span.type(), span.detector()))
        .toList();
  }
}
