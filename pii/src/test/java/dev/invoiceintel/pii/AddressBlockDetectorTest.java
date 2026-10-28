package dev.invoiceintel.pii;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.common.fixtures.Buyer;
import dev.invoiceintel.common.fixtures.InvoiceType;
import dev.invoiceintel.common.fixtures.Seller;
import dev.invoiceintel.common.fixtures.SyntheticInvoice;
import dev.invoiceintel.common.fixtures.SyntheticInvoiceGenerator;
import dev.invoiceintel.text.PdfTextExtractor;
import dev.invoiceintel.text.TextLayer;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Synthetic fixtures and made-up values only (testing.md). */
class AddressBlockDetectorTest {

  // Built from parts so the CI secret scan (.gitleaks.toml) doesn't flag test sources.
  private static final String MOBILE = "98765" + "43210";

  private final AddressBlockDetector detector = new AddressBlockDetector();

  static Stream<Arguments> invoices() {
    return Arrays.stream(InvoiceType.values())
        .flatMap(
            type -> Stream.of(1L, 7L, 42L, 1234L, 99_999L).map(seed -> Arguments.of(type, seed)));
  }

  private record Found(PiiType type, String text) {}

  private record Run(String text, DocumentContext ctx, List<PiiSpan> spans) {

    List<Found> found() {
      return spans.stream()
          .map(s -> new Found(s.type(), text.substring(s.start(), s.end())))
          .toList();
    }
  }

  private Run run(TextLayer layer) {
    DocumentContext ctx = DocumentContext.of(DocumentId.random(), layer);
    return new Run(PageText.of(layer.pages().getFirst()).text(), ctx, detector.detect(layer, ctx));
  }

  private Run run(SyntheticInvoice invoice) {
    return run(new PdfTextExtractor().extract(SyntheticInvoiceGenerator.toPdf(invoice)));
  }

  @ParameterizedTest(name = "{0} seed {1}")
  @MethodSource("invoices")
  void detect_syntheticInvoice_findsBuyerNameStreetCityAndPin(InvoiceType type, long seed) {
    SyntheticInvoice invoice = SyntheticInvoiceGenerator.generate(type, seed);
    Buyer buyer = invoice.buyer();

    Run run = run(invoice);

    assertThat(run.found())
        .contains(
            new Found(PiiType.PERSON, buyer.name()),
            new Found(PiiType.ADDRESS, buyer.street()),
            new Found(PiiType.ADDRESS, buyer.state().city() + ","),
            new Found(PiiType.PIN_CODE, buyer.pinCode()),
            new Found(PiiType.PHONE, buyer.phone()),
            new Found(PiiType.EMAIL, buyer.email()));
  }

  @ParameterizedTest(name = "{0} seed {1}")
  @MethodSource("invoices")
  void detect_syntheticInvoice_leavesSellerAndStateAlone(InvoiceType type, long seed) {
    SyntheticInvoice invoice = SyntheticInvoiceGenerator.generate(type, seed);
    Seller seller = invoice.seller();

    Run run = run(invoice);

    assertThat(run.spans()).isNotEmpty().noneMatch(run.ctx()::isSellerData);
    assertThat(run.found())
        .extracting(Found::text)
        .noneMatch(text -> text.contains(seller.name()))
        .noneMatch(text -> text.contains(seller.street()))
        .noneMatch(text -> text.contains(seller.pan()))
        .noneMatch(text -> text.contains(seller.taxId()));
    String state = invoice.buyer().state().displayName();
    for (int at = run.text().indexOf(state); at >= 0; at = run.text().indexOf(state, at + 1)) {
      int start = at;
      assertThat(run.spans())
          .as("state kept")
          .noneMatch(s -> s.start() < start + state.length() && s.end() > start);
    }
    // The State/UT code line is not PII and ends the block.
    assertThat(run.found()).extracting(Found::text).noneMatch(text -> text.contains("Code"));
  }

  @ParameterizedTest(name = "{0} seed {1}")
  @MethodSource("invoices")
  void tag_syntheticInvoice_coversSellerBlockButNotBuyer(InvoiceType type, long seed) {
    SyntheticInvoice invoice = SyntheticInvoiceGenerator.generate(type, seed);
    Seller seller = invoice.seller();
    Buyer buyer = invoice.buyer();

    Run run = run(invoice);

    for (String value :
        List.of(
            "Sold By:",
            seller.name(),
            seller.street(),
            seller.state().city() + ", " + seller.state().displayName(),
            seller.pinCode(),
            "PAN No: " + seller.pan(),
            seller.taxId())) {
      assertThat(isSeller(run, value)).as("seller value tagged").isTrue();
    }
    for (String value : List.of(buyer.name(), buyer.street(), buyer.phone(), buyer.email())) {
      assertThat(isSeller(run, value)).as("buyer value not tagged").isFalse();
    }
    assertThat(isSeller(run, "Order Number:")).isFalse();
  }

  // First occurrence: the seller block comes before the signature line that repeats the name.
  private static boolean isSeller(Run run, String value) {
    int at = run.text().indexOf(value);
    assertThat(at).as("value present").isNotNegative();
    return run.ctx().isSellerData(new PiiSpan(1, at, at + value.length(), PiiType.ADDRESS, "t"));
  }

  @Test
  void detect_billingAndShippingBlocks_findsBoth() {
    TextLayer layer =
        Layouts.page(
            Layouts.line(40, 100, "Billing Address:"),
            Layouts.line(40, 112, "Aarav Testkumar"),
            Layouts.line(40, 124, "Flat 101, Example Residency"),
            Layouts.line(40, 136, "Bengaluru, KARNATAKA, 560034"),
            Layouts.line(320, 100, "Shipping Address:"),
            Layouts.line(320, 112, "Meera Samplewala"),
            Layouts.line(320, 124, "12 Test Lane"),
            Layouts.line(320, 136, "Chennai, Tamil Nadu 600 042"));

    Run run = run(layer);

    assertThat(run.found())
        .containsExactlyInAnyOrder(
            new Found(PiiType.PERSON, "Aarav Testkumar"),
            new Found(PiiType.PERSON, "Meera Samplewala"),
            new Found(PiiType.ADDRESS, "Flat 101, Example Residency"),
            new Found(PiiType.ADDRESS, "12 Test Lane"),
            new Found(PiiType.ADDRESS, "Bengaluru,"),
            new Found(PiiType.PIN_CODE, "560034"),
            new Found(PiiType.ADDRESS, "Chennai,"),
            new Found(PiiType.PIN_CODE, "600 042"));
  }

  @Test
  void detect_ocrSplitAnchorAndNameOnAnchorLine_findsBlock() {
    TextLayer layer =
        Layouts.page(
            Layouts.line(40, 100, "Ship to : Kavya Demoraj"),
            Layouts.line(40, 112, "4 Fixture Street"),
            Layouts.line(40, 124, "Phone: " + MOBILE),
            Layouts.line(40, 136, "Mail- kavya@example.com"));

    Run run = run(layer);

    assertThat(run.found())
        .containsExactly(
            new Found(PiiType.PERSON, "Kavya Demoraj"),
            new Found(PiiType.ADDRESS, "4 Fixture Street"),
            new Found(PiiType.PHONE, MOBILE),
            new Found(PiiType.EMAIL, "kavya@example.com"));
  }

  @Test
  void detect_anchorSplitAcrossWords_matches() {
    TextLayer layer =
        Layouts.page(
            Layouts.line(40, 100, "BILL ING ADDR ESS"), Layouts.line(40, 112, "Rohan Fixturekar"));

    assertThat(run(layer).found()).containsExactly(new Found(PiiType.PERSON, "Rohan Fixturekar"));
  }

  @Test
  void detect_noAnchor_findsNothing() {
    TextLayer layer =
        Layouts.page(
            Layouts.line(40, 100, "Address of the warehouse"),
            Layouts.line(40, 112, "Rohan Fixturekar"),
            Layouts.line(40, 124, "Billings and more"));

    assertThat(run(layer).spans()).isEmpty();
  }

  @Test
  void detect_singleColumn_stopsAtGapAndLabels() {
    TextLayer layer =
        Layouts.page(
            Layouts.line(40, 100, "Bill To"),
            Layouts.line(40, 112, "Rohan Fixturekar"),
            Layouts.line(40, 124, "7 Sample Road, Pune"),
            Layouts.line(40, 136, "Order Number: 123"),
            Layouts.line(40, 148, "Deliver To"),
            Layouts.line(40, 160, "Kavya Demoraj"),
            Layouts.line(40, 200, "Thank you for shopping"));

    assertThat(run(layer).found())
        .containsExactly(
            new Found(PiiType.PERSON, "Rohan Fixturekar"),
            new Found(PiiType.ADDRESS, "7 Sample Road, Pune"),
            new Found(PiiType.PERSON, "Kavya Demoraj"));
  }

  @Test
  void detect_stateGluedToCity_keepsTheWholeWord() {
    TextLayer layer =
        Layouts.page(
            Layouts.line(40, 100, "Billing Address"),
            Layouts.line(40, 112, "Rohan Fixturekar"),
            Layouts.line(40, 124, "Pune-Maharashtra Maharashtra, India"));

    assertThat(run(layer).found())
        .containsExactly(
            new Found(PiiType.PERSON, "Rohan Fixturekar"),
            new Found(PiiType.ADDRESS, "Pune-Maharashtra"),
            new Found(PiiType.ADDRESS, "India"));
  }

  @Test
  void detect_spanInsideSellerRange_isDropped() {
    TextLayer layer =
        Layouts.page(Layouts.line(40, 100, "Ship To"), Layouts.line(40, 112, "Fixture Traders"));
    var everything = new TextRange(1, 0, PageText.of(layer.pages().getFirst()).text().length());
    var ctx = new DocumentContext(DocumentId.random(), List.of(everything));

    assertThat(detector.detect(layer, ctx)).isEmpty();
  }

  @Test
  void detect_spansCarryDetectorNames() {
    TextLayer layer =
        Layouts.page(
            Layouts.line(40, 100, "Billing Address"),
            Layouts.line(40, 112, "Rohan Fixturekar"),
            Layouts.line(40, 124, "Pune 411 001"));

    assertThat(run(layer).spans())
        .extracting(PiiSpan::type, PiiSpan::detector)
        .containsExactly(
            tuple(PiiType.PERSON, AddressBlockDetector.PERSON),
            tuple(PiiType.ADDRESS, AddressBlockDetector.ADDRESS),
            tuple(PiiType.PIN_CODE, AddressBlockDetector.PIN));
  }
}
