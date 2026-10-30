package dev.invoiceintel.validate;

import static org.assertj.core.api.Assertions.assertThat;

import dev.invoiceintel.common.DocumentClass;
import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.common.extraction.Field;
import dev.invoiceintel.common.extraction.InvoiceExtraction;
import dev.invoiceintel.common.extraction.LineItem;
import dev.invoiceintel.common.extraction.TaxLine;
import dev.invoiceintel.common.extraction.TaxType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class TaxRegimeDateRuleTest {

  private static final ValidationContext CONTEXT =
      new ValidationContext(new DocumentId(UUID.randomUUID()), DocumentClass.TAX_INVOICE);
  private static final String SYNTHETIC_GSTIN = "27AAAAA0000A1Z5";

  private final TaxRegimeDateRule rule = new TaxRegimeDateRule();

  @Test
  void id_isVal6() {
    assertThat(rule.id()).isEqualTo("VAL-6");
  }

  @Test
  void gstStart_isFirstJuly2017() {
    assertThat(TaxRegimeDateRule.GST_START).isEqualTo(LocalDate.of(2017, 7, 1));
  }

  @Test
  void consistentFixture_passes() {
    assertThat(rule.check(ExtractionFixtures.consistentTaxInvoice(), CONTEXT))
        .isEqualTo(RuleResult.pass());
  }

  @ParameterizedTest(name = "{0} with {1} -> {2}")
  @CsvSource({
    // last pre-GST day
    "2017-06-30, CGST SGST, FAIL",
    "2017-06-30, IGST, FAIL",
    "2017-06-30, UTGST, FAIL",
    "2017-06-30, VAT, PASS",
    "2017-06-30, CST, PASS",
    "2017-06-30, VAT CESS, PASS",
    "2017-06-30, VAT IGST, FAIL",
    // first GST day
    "2017-07-01, CGST SGST, PASS",
    "2017-07-01, IGST CESS, PASS",
    "2017-07-01, UTGST, PASS",
    "2017-07-01, VAT, FAIL",
    "2017-07-01, CST, FAIL",
    "2017-07-01, IGST CST, FAIL",
    // next to the boundary
    "2017-06-29, SGST, FAIL",
    "2017-07-02, VAT, FAIL",
    // far dates
    "1990-01-01, VAT, PASS",
    "1990-01-01, IGST, FAIL",
    "2005-04-01, CST, PASS",
    "2099-12-31, CGST SGST, PASS",
    "2099-12-31, VAT, FAIL",
  })
  void check_boundaryAndFarDates(String date, String types, RuleOutcome expected) {
    InvoiceExtraction extraction = extraction(LocalDate.parse(date), null, taxTypes(types));

    assertThat(rule.check(extraction, CONTEXT).outcome()).isEqualTo(expected);
  }

  @ParameterizedTest
  @ValueSource(strings = {"2017-06-30", "2017-07-01"})
  void cessAlone_passesInBothEras(String date) {
    assertThat(rule.check(extraction(LocalDate.parse(date), null, List.of(TaxType.CESS)), CONTEXT))
        .isEqualTo(RuleResult.pass());
  }

  @Test
  void preGstFail_namesDateAndOffendingTypesOnly() {
    RuleResult result =
        rule.check(
            extraction(
                LocalDate.of(2017, 6, 30),
                null,
                List.of(TaxType.SGST, TaxType.VAT, TaxType.CGST, TaxType.CGST)),
            CONTEXT);

    assertThat(result)
        .isEqualTo(
            RuleResult.fail(
                "invoiceDate 2017-06-30 is before GST (2017-07-01) but has CGST, SGST"));
  }

  @Test
  void gstEraFail_namesDateAndOffendingTypesOnly() {
    RuleResult result =
        rule.check(
            extraction(
                LocalDate.of(2017, 7, 1), null, List.of(TaxType.CST, TaxType.IGST, TaxType.VAT)),
            CONTEXT);

    assertThat(result)
        .isEqualTo(
            RuleResult.fail(
                "invoiceDate 2017-07-01 is in the GST era (from 2017-07-01) but has VAT, CST"));
  }

  @Test
  void preGstWithSellerGstin_warnsWithoutQuotingIt() {
    RuleResult result =
        rule.check(
            extraction(LocalDate.of(2017, 6, 30), SYNTHETIC_GSTIN, List.of(TaxType.VAT)), CONTEXT);

    assertThat(result.outcome()).isEqualTo(RuleOutcome.WARN);
    assertThat(result.message()).contains("sellerGstin").doesNotContain(SYNTHETIC_GSTIN);
  }

  @Test
  void preGstWithSellerGstinAndGstTax_fails() {
    RuleResult result =
        rule.check(
            extraction(LocalDate.of(2016, 1, 1), SYNTHETIC_GSTIN, List.of(TaxType.IGST)), CONTEXT);

    assertThat(result.outcome()).isEqualTo(RuleOutcome.FAIL);
  }

  @Test
  void gstEraWithSellerGstin_passes() {
    assertThat(
            rule.check(
                extraction(LocalDate.of(2017, 7, 1), SYNTHETIC_GSTIN, List.of(TaxType.IGST)),
                CONTEXT))
        .isEqualTo(RuleResult.pass());
  }

  @ParameterizedTest
  @ValueSource(strings = {"CGST SGST", "VAT", "CST", "IGST"})
  void missingDate_withEraSpecificTax_warns(String types) {
    RuleResult result = rule.check(extraction(null, null, taxTypes(types)), CONTEXT);

    assertThat(result)
        .isEqualTo(RuleResult.warn("invoiceDate missing; tax regime can't be checked"));
  }

  @Test
  void missingDate_withSellerGstinOnly_warns() {
    assertThat(rule.check(extraction(null, SYNTHETIC_GSTIN, List.of()), CONTEXT).outcome())
        .isEqualTo(RuleOutcome.WARN);
  }

  @Test
  void missingDate_withoutEraSpecificFields_passes() {
    assertThat(rule.check(extraction(null, null, List.of(TaxType.CESS)), CONTEXT))
        .isEqualTo(RuleResult.pass());
    assertThat(rule.check(extraction(null, null, List.of()), CONTEXT)).isEqualTo(RuleResult.pass());
  }

  private static List<TaxType> taxTypes(String spaceSeparated) {
    return Arrays.stream(spaceSeparated.trim().split("\\s+")).map(TaxType::valueOf).toList();
  }

  /**
   * The consistent fixture with its invoice date, seller GSTIN and tax types replaced. Each tax
   * type goes on its own line item with one tax line; an empty list gives one line without taxes.
   */
  private static InvoiceExtraction extraction(
      LocalDate date, String sellerGstin, List<TaxType> taxTypes) {
    InvoiceExtraction base = ExtractionFixtures.consistentTaxInvoice();
    LineItem template = base.lineItems().getFirst();
    List<LineItem> items =
        taxTypes.isEmpty()
            ? List.of(withTaxes(template, List.of()))
            : taxTypes.stream().map(type -> withTaxes(template, List.of(taxLine(type)))).toList();
    return new InvoiceExtraction(
        base.documentClass(),
        base.invoiceNumber(),
        date == null ? null : Field.llm(date, 0.9),
        base.orderNumber(),
        base.orderDate(),
        base.sellerName(),
        sellerGstin == null ? null : Field.llm(sellerGstin, 0.9),
        base.sellerPan(),
        base.sellerAddress(),
        base.placeOfSupply(),
        base.placeOfDelivery(),
        base.buyerState(),
        base.reverseCharge(),
        base.currency(),
        base.subtotal(),
        base.totalTax(),
        base.grandTotal(),
        base.amountInWords(),
        base.originalInvoiceRef(),
        items);
  }

  private static TaxLine taxLine(TaxType type) {
    return new TaxLine(
        Field.llm(type, 0.95),
        Field.llm(new BigDecimal("9"), 0.95),
        Field.llm(new BigDecimal("90.00"), 0.9));
  }

  private static LineItem withTaxes(LineItem item, List<TaxLine> taxes) {
    return new LineItem(
        item.lineNo(),
        item.chargeType(),
        item.description(),
        item.hsnSac(),
        item.unitPrice(),
        item.quantity(),
        item.discount(),
        item.netAmount(),
        taxes,
        item.lineTotal());
  }
}
