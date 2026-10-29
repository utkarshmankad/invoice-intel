package dev.invoiceintel.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.invoiceintel.common.DocumentClass;
import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.pii.RedactedContent;
import dev.invoiceintel.pii.RedactedContentFixtures;
import dev.invoiceintel.text.Word;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Evidence mapping from line markers to word boxes (M3.6, REV-2). Synthetic, already pseudonymized
 * text only. The fixture lays words out from x = 40, y = 40 + 14 per line, 12 high, 6 per character
 * with a 6-wide gap, so the expected boxes follow from the text.
 */
class EvidenceMapperTest {

  private static final RedactedContent CONTENT =
      RedactedContentFixtures.of(
          DocumentId.random(),
          List.of(
              "Tax Invoice\nInvoice No INVNO_TOKEN_01\nSold By Example Traders",
              "Grand Total 1180.00\nTaxable 1000.00 CGST 9 90.00"));

  /** p1:l2 "Invoice No INVNO_TOKEN_01": x 40–190, y 54–66. */
  private static final BoundingBox P1_L2 = new BoundingBox(40, 54, 150, 12);

  /** p2:l1 "Grand Total 1180.00": x 40–154, y 40–52. */
  private static final BoundingBox P2_L1 = new BoundingBox(40, 40, 114, 12);

  private static Evidence cited(int page, String... refs) {
    return new Evidence(page, null, List.of(refs));
  }

  private static <T> Field<T> llm(T value, Evidence evidence) {
    return new Field<>(value, 0.9, FieldSource.LLM, evidence);
  }

  @Test
  void map_citedLine_setsPageAndBoxFromLineWords() {
    InvoiceExtraction mapped =
        EvidenceMapper.map(extraction(llm("INVNO_TOKEN_01", cited(1, "p1:l2"))), CONTENT);

    assertThat(mapped.invoiceNumber().evidence())
        .isEqualTo(new Evidence(1, P1_L2, List.of("p1:l2")));
    assertThat(mapped.invoiceNumber().value()).isEqualTo("INVNO_TOKEN_01");
    assertThat(mapped.invoiceNumber().confidence()).isEqualTo(0.9);
  }

  @Test
  void map_severalLinesOnOnePage_boxCoversAllOfThem() {
    InvoiceExtraction mapped =
        EvidenceMapper.map(extraction(llm("INVNO_TOKEN_01", cited(1, "p1:l1", "p1:l2"))), CONTENT);

    // "Tax Invoice" (y 40–52) and line 2 (y 54–66, right edge 190).
    assertThat(mapped.invoiceNumber().evidence().box()).isEqualTo(new BoundingBox(40, 40, 150, 26));
  }

  @Test
  void map_modelPageDiffersFromMarker_pageComesFromMarker() {
    Field<BigDecimal> total = llm(new BigDecimal("1180.00"), cited(1, "p2:l1"));

    InvoiceExtraction mapped = EvidenceMapper.map(withGrandTotal(total), CONTENT);

    assertThat(mapped.grandTotal().evidence().page()).isEqualTo(2);
    assertThat(mapped.grandTotal().evidence().box()).isEqualTo(P2_L1);
  }

  @Test
  void map_linesOnTwoPages_keepsLinesOnModelPage() {
    Field<BigDecimal> total = llm(new BigDecimal("1180.00"), cited(2, "p1:l2", "p2:l1"));

    InvoiceExtraction mapped = EvidenceMapper.map(withGrandTotal(total), CONTENT);

    assertThat(mapped.grandTotal().evidence())
        .isEqualTo(new Evidence(2, P2_L1, List.of("p1:l2", "p2:l1")));
  }

  @Test
  void map_linesOnTwoPagesNoneOnModelPage_keepsFirstCitedPage() {
    Field<BigDecimal> total = llm(new BigDecimal("1180.00"), cited(5, "p2:l1", "p1:l2"));

    InvoiceExtraction mapped = EvidenceMapper.map(withGrandTotal(total), CONTENT);

    assertThat(mapped.grandTotal().evidence().page()).isEqualTo(2);
    assertThat(mapped.grandTotal().evidence().box()).isEqualTo(P2_L1);
  }

  @Test
  void map_bracketedOrUpperCaseMarker_isAccepted() {
    InvoiceExtraction mapped =
        EvidenceMapper.map(extraction(llm("INVNO_TOKEN_01", cited(1, " [P1:L2] "))), CONTENT);

    assertThat(mapped.invoiceNumber().evidence().box()).isEqualTo(P1_L2);
    assertThat(mapped.invoiceNumber().evidence().lineRefs()).containsExactly(" [P1:L2] ");
  }

  @Test
  void map_unknownOrMalformedMarkers_ignoredAndOthersStillMapped() {
    InvoiceExtraction mapped =
        EvidenceMapper.map(
            extraction(llm("INVNO_TOKEN_01", cited(1, "p1:l99", "line 2", "p9:l1", "p1:l2"))),
            CONTENT);

    assertThat(mapped.invoiceNumber().evidence().box()).isEqualTo(P1_L2);
  }

  @Test
  void map_noMarkerResolves_clearsModelBoxAndKeepsPage() {
    // A box the model made up has no known unit, so it never survives mapping.
    var invented = new Evidence(2, new BoundingBox(1, 2, 3, 4), List.of("P1L24", "p3:l1"));

    InvoiceExtraction mapped =
        EvidenceMapper.map(extraction(llm("INVNO_TOKEN_01", invented)), CONTENT);

    assertThat(mapped.invoiceNumber().evidence())
        .isEqualTo(new Evidence(2, null, List.of("P1L24", "p3:l1")));
  }

  @Test
  void map_ruleOrHumanEvidence_isKept() {
    var humanBox = new Evidence(1, new BoundingBox(10, 10, 20, 20), List.of("p1:l2"));
    var human = new Field<>("INVNO_TOKEN_01", 1.0, FieldSource.HUMAN, humanBox);

    InvoiceExtraction mapped = EvidenceMapper.map(extraction(human), CONTENT);

    assertThat(mapped.invoiceNumber()).isSameAs(human);
  }

  @Test
  void map_fieldsWithoutEvidenceOrAbsent_areUnchanged() {
    InvoiceExtraction input = extraction(Field.llm("INVNO_TOKEN_01", 0.9));

    InvoiceExtraction mapped = EvidenceMapper.map(input, CONTENT);

    assertThat(mapped.invoiceNumber()).isSameAs(input.invoiceNumber());
    assertThat(mapped.currency()).isSameAs(input.currency());
    assertThat(mapped.originalInvoiceRef()).isNull();
    assertThat(mapped.lineItems().getFirst().discount()).isNull();
  }

  @Test
  void map_lineItemAndTaxLineFields_areMapped() {
    InvoiceExtraction mapped =
        EvidenceMapper.map(extraction(llm("INVNO_TOKEN_01", cited(1, "p1:l2"))), CONTENT);

    LineItem item = mapped.lineItems().getFirst();
    // p2:l2 "Taxable 1000.00 CGST 9 90.00": x 40–208, y 54–66.
    assertThat(item.netAmount().evidence().box()).isEqualTo(new BoundingBox(40, 54, 168, 12));
    assertThat(item.taxLines().getFirst().amount().evidence())
        .isEqualTo(new Evidence(2, new BoundingBox(40, 54, 168, 12), List.of("p2:l2")));
  }

  @Test
  void map_wordsLeftOfOrAbovePageOrigin_boxIsClampedToPage() {
    // OCR boxes can start a little outside the page; BoundingBox is non-negative.
    RedactedContent content =
        RedactedContentFixtures.ofWords(
            DocumentId.random(), List.of(List.of(new Word("Total", -3, -2, 30, 12, 0.9))));

    InvoiceExtraction mapped =
        EvidenceMapper.map(extraction(llm("INVNO_TOKEN_01", cited(1, "p1:l1"))), content);

    assertThat(mapped.invoiceNumber().evidence().box()).isEqualTo(new BoundingBox(0, 0, 27, 10));
  }

  @Test
  void map_nullArguments_rejected() {
    InvoiceExtraction extraction = extraction(Field.llm("INVNO_TOKEN_01", 0.9));

    assertThatThrownBy(() -> EvidenceMapper.map(null, CONTENT))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> EvidenceMapper.map(extraction, null))
        .isInstanceOf(NullPointerException.class);
  }

  private static InvoiceExtraction withGrandTotal(Field<BigDecimal> grandTotal) {
    InvoiceExtraction e = extraction(Field.llm("INVNO_TOKEN_01", 0.9));
    return new InvoiceExtraction(
        e.documentClass(),
        e.invoiceNumber(),
        e.invoiceDate(),
        e.orderNumber(),
        e.orderDate(),
        e.sellerName(),
        e.sellerGstin(),
        e.sellerPan(),
        e.sellerAddress(),
        e.placeOfSupply(),
        e.placeOfDelivery(),
        e.buyerState(),
        e.reverseCharge(),
        e.currency(),
        e.subtotal(),
        e.totalTax(),
        grandTotal,
        e.amountInWords(),
        e.originalInvoiceRef(),
        e.lineItems());
  }

  /** A small extraction whose invoice number is {@code invoiceNumber}; line fields cite p2. */
  private static InvoiceExtraction extraction(Field<String> invoiceNumber) {
    Evidence taxRow = cited(2, "p2:l2");
    TaxLine cgst =
        new TaxLine(
            Field.llm(TaxType.CGST, 0.95),
            Field.llm(new BigDecimal("9"), 0.95),
            llm(new BigDecimal("90.00"), taxRow));
    LineItem item =
        new LineItem(
            Field.llm(1, 1.0),
            Field.llm(ChargeType.PRODUCT, 0.9),
            Field.llm("Steel water bottle", 0.9),
            null,
            Field.llm(new BigDecimal("1000.00"), 0.9),
            Field.llm(BigDecimal.ONE, 0.95),
            null,
            llm(new BigDecimal("1000.00"), taxRow),
            List.of(cgst),
            Field.llm(new BigDecimal("1090.00"), 0.9));
    return new InvoiceExtraction(
        Field.llm(DocumentClass.TAX_INVOICE, 0.95),
        invoiceNumber,
        Field.llm(LocalDate.of(2025, 3, 14), 0.9),
        Field.llm("ORDNO_TOKEN_01", 0.9),
        null,
        llm("Example Traders", cited(1, "p1:l3")),
        null,
        null,
        null,
        null,
        null,
        Field.llm("Maharashtra", 0.9),
        null,
        new Field<>(Currency.INR, 1.0, FieldSource.RULE, null),
        Field.llm(new BigDecimal("1000.00"), 0.9),
        Field.llm(new BigDecimal("90.00"), 0.9),
        Field.llm(new BigDecimal("1090.00"), 0.9),
        null,
        null,
        List.of(item));
  }
}
