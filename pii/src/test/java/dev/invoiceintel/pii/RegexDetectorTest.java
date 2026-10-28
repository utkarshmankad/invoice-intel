package dev.invoiceintel.pii;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.common.fixtures.InvoiceType;
import dev.invoiceintel.common.fixtures.SyntheticInvoice;
import dev.invoiceintel.common.fixtures.SyntheticInvoiceGenerator;
import dev.invoiceintel.text.PdfTextExtractor;
import dev.invoiceintel.text.TextLayer;
import dev.invoiceintel.text.TextPage;
import dev.invoiceintel.text.TextSource;
import dev.invoiceintel.text.Word;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Synthetic fixtures and made-up values only (testing.md). */
class RegexDetectorTest {

  private static final DocumentContext CTX = new DocumentContext(DocumentId.random());

  private final RegexDetector detector = new RegexDetector();

  @ParameterizedTest(name = "{1} in: {0}")
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          Email: aarav.testkumar@example.com                 | EMAIL          | aarav.testkumar@example.com
          contact meera_s+orders@mail.example.co.in today    | EMAIL          | meera_s+orders@mail.example.co.in
          Phone: +91 9876543210                              | PHONE          | +91 9876543210
          Mobile +91-98765-43210                             | PHONE          | +91-98765-43210
          Ph 919876543210                                    | PHONE          | 919876543210
          call 09876543210 now                               | PHONE          | 09876543210
          Mob: 98765 43210                                   | PHONE          | 98765 43210
          Tel 080-41234567                                   | PHONE          | 080-41234567
          Tel 0141 2345678                                   | PHONE          | 0141 2345678
          Tel +91 80 41234567                                | PHONE          | +91 80 41234567
          PIN: 560034                                        | PIN_CODE       | 560034
          Pincode - 560 034                                  | PIN_CODE       | 560 034
          Bengaluru, Karnataka 560034                        | PIN_CODE       | 560034
          MUMBAI, MAHARASHTRA, 400001, IN                    | PIN_CODE       | 400001
          Kolkata, West  Bengal 700019                       | PIN_CODE       | 700019
          New Delhi 110001                                   | PIN_CODE       | 110001
          Bengaluru, Karnataka Mumbai, Maharashtra 560034 400001 | PIN_CODE   | 400001
          BENGALURU-560034                                   | PIN_CODE       | 560034
          PAN No: ABCPT1234K                                 | PAN            | ABCPT1234K
          PAN AAACS1234F                                     | PAN            | AAACS1234F
          Order Number: 402-1234567-7654321                  | ORDER_NUMBER   | 402-1234567-7654321
          orders 171-0000001-0000002 and more                | ORDER_NUMBER   | 171-0000001-0000002
          Order ID # OD123456789012                          | ORDER_NUMBER   | OD123456789012
          Order No. 12345678                                 | ORDER_NUMBER   | 12345678
          Invoice Number: INV-29-1234567                     | INVOICE_NUMBER | INV-29-1234567
          Invoice No.: BLR7/2023/0042                        | INVOICE_NUMBER | BLR7/2023/0042
          Invoice # KA-12345                                 | INVOICE_NUMBER | KA-12345
          Credit Note Number: CN-27-7654321                  | INVOICE_NUMBER | CN-27-7654321
          Original Invoice Number: INV-27-0000042            | INVOICE_NUMBER | INV-27-0000042
          Bill No 98765X                                     | INVOICE_NUMBER | 98765X
          """)
  void detect_piiValue_isFoundWithType(String text, PiiType type, String value) {
    List<String> found = found(text, type);

    assertThat(found).contains(value);
  }

  @ParameterizedTest(name = "nothing in: {0}")
  @ValueSource(
      strings = {
        "HSN 847130 Qty 2",
        "Unit Price 560034.00 Net",
        "Total 1,23,456.00",
        "Invoice Date: 2021-03-12 Order Date: 2021-03-10",
        "Invoice Details Order Details",
        "Order Number: pending",
        "Bill of Supply",
        "USB-C Charging Cable 1m",
        "Amount 12345678901 rupees",
        "abcde1234f",
        "GSTIN 29ABCPT1234K1Z5",
        "Pin it to the board 4",
        "code 1234567-1234567",
        "Karnataka 5600341",
        "user at example dot com"
      })
  void detect_nonPii_findsNothing(String text) {
    assertThat(detector.detect(layer(text), CTX)).isEmpty();
  }

  @Test
  void detect_spanOffsets_pointIntoPageText() {
    TextLayer layer = layer("Call +91 9876543210 or mail a.b@example.com");

    List<PiiSpan> spans = detector.detect(layer, CTX);

    String text = PageText.of(layer.pages().getFirst()).text();
    assertThat(spans)
        .extracting(span -> text.substring(span.start(), span.end()))
        .containsOnly("+91 9876543210", "a.b@example.com");
    // The landline rule also accepts +91 followed by ten digits; SpanMerger unions duplicates.
    assertThat(spans)
        .extracting(PiiSpan::detector)
        .containsOnly("regex:mobile", "regex:landline", "regex:email");
    assertThat(spans).allSatisfy(span -> assertThat(span.pageNo()).isEqualTo(1));
  }

  @Test
  void detect_severalPages_reportsEachPage() {
    var layer =
        new TextLayer(
            List.of(page(1, "Phone 9876543210"), page(2, "nothing here"), page(3, "PIN 110001")));

    assertThat(detector.detect(layer, CTX))
        .extracting(PiiSpan::pageNo, PiiSpan::type)
        .containsExactlyInAnyOrder(tuple(1, PiiType.PHONE), tuple(3, PiiType.PIN_CODE));
  }

  static Stream<Arguments> fixtures() {
    return Arrays.stream(InvoiceType.values())
        .flatMap(type -> Stream.of(3L, 17L, 99L).map(seed -> Arguments.of(type, seed)));
  }

  @ParameterizedTest(name = "{0} seed {1}")
  @MethodSource("fixtures")
  void detect_syntheticInvoice_findsBuyerContactAndDocumentNumbers(InvoiceType type, long seed) {
    SyntheticInvoice invoice = SyntheticInvoiceGenerator.generate(type, seed);
    TextLayer layer = new PdfTextExtractor().extract(SyntheticInvoiceGenerator.toPdf(invoice));
    String text = PageText.of(layer.pages().getFirst()).text();

    List<String> found =
        detector.detect(layer, CTX).stream()
            .map(span -> text.substring(span.start(), span.end()))
            .toList();

    assertThat(found)
        .contains(
            invoice.buyer().phone(),
            invoice.buyer().email(),
            invoice.buyer().pinCode(),
            invoice.orderNumber(),
            invoice.invoiceNumber());
    invoice.creditedInvoiceNumber().ifPresent(number -> assertThat(found).contains(number));
  }

  @ParameterizedTest(name = "{0} seed {1}")
  @MethodSource("fixtures")
  void detect_syntheticInvoice_leavesHsnAmountsAndDatesAlone(InvoiceType type, long seed) {
    SyntheticInvoice invoice = SyntheticInvoiceGenerator.generate(type, seed);
    TextLayer layer = new PdfTextExtractor().extract(SyntheticInvoiceGenerator.toPdf(invoice));
    String text = PageText.of(layer.pages().getFirst()).text();
    List<PiiSpan> spans = detector.detect(layer, CTX);

    var notPii =
        Stream.concat(
                invoice.lineItems().stream()
                    .flatMap(
                        item ->
                            Stream.of(
                                item.hsnCode(),
                                item.description(),
                                item.netAmount().toPlainString(),
                                item.totalAmount().toPlainString())),
                Stream.of(
                    invoice.grandTotal().toPlainString(),
                    invoice.invoiceDate().toString(),
                    invoice.orderDate().toString()))
            .toList();
    for (String value : notPii) {
      // Whole values only: an HSN code can also occur inside a document number.
      Matcher m =
          Pattern.compile("(?<![A-Za-z0-9-])" + Pattern.quote(value) + "(?![A-Za-z0-9])")
              .matcher(text);
      assertThat(m.find()).as("fixture prints %s", value).isTrue();
      do {
        int start = m.start();
        int end = m.end();
        assertThat(spans)
            .as("no span over %s", value)
            .noneMatch(span -> span.start() < end && span.end() > start);
      } while (m.find());
    }
  }

  @Test
  void rules_haveUniqueNames() {
    assertThat(RegexDetector.RULES).extracting(RegexDetector.Rule::name).doesNotHaveDuplicates();
  }

  private List<String> found(String text, PiiType type) {
    TextLayer layer = layer(text);
    String pageText = PageText.of(layer.pages().getFirst()).text();
    return detector.detect(layer, CTX).stream()
        .filter(span -> span.type() == type)
        .map(span -> pageText.substring(span.start(), span.end()))
        .toList();
  }

  private static TextLayer layer(String text) {
    return new TextLayer(List.of(page(1, text)));
  }

  static TextPage page(int pageNo, String text) {
    List<Word> words =
        Arrays.stream(text.strip().split("\\s+"))
            .map(word -> new Word(word, 0, 0, 1, 1, 1))
            .toList();
    return new TextPage(pageNo, 100, 100, TextSource.PDF_TEXT, words);
  }
}
