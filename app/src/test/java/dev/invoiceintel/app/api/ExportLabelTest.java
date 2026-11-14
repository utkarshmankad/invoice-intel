package dev.invoiceintel.app.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.invoiceintel.common.DocumentClass;
import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.common.extraction.ChargeType;
import dev.invoiceintel.common.extraction.Currency;
import dev.invoiceintel.common.extraction.Field;
import dev.invoiceintel.common.extraction.FieldSource;
import dev.invoiceintel.common.extraction.InvoiceExtraction;
import dev.invoiceintel.common.extraction.LineItem;
import dev.invoiceintel.common.extraction.TaxLine;
import dev.invoiceintel.common.extraction.TaxType;
import dev.invoiceintel.eval.label.LabelValidator;
import dev.invoiceintel.pipeline.ReviewedExtraction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.node.ObjectNode;

/** REV-4, ADR-31: a reviewed extraction becomes a pseudonymized label that validates (M5.1). */
class ExportLabelTest {

  private static final String SHA = "ab".repeat(32);

  @Test
  void of_reviewedExtraction_isValidPseudonymizedLabel() {
    ObjectNode label = ExportLabel.of(reviewed(extraction(lines(line(1, "Example widget")))));

    assertThat(LabelValidator.validate(label, SHA + ".json")).isEmpty();
    assertThat(label.get("pseudonymized").booleanValue()).isTrue();
    assertThat(label.get("synthetic").booleanValue()).isFalse();
    assertThat(label.get("piiSpans")).isEmpty();
    var fields = label.get("fields");
    assertThat(fields.get("invoiceNumber").stringValue()).isEqualTo("tok_inv_0001");
    assertThat(fields.get("orderNumber").stringValue()).isEqualTo("tok_ord_0001");
    assertThat(fields.get("originalInvoiceRef").isNull()).isTrue();
    assertThat(fields.get("invoiceDate").stringValue()).isEqualTo("2026-01-15");
    assertThat(fields.get("grandTotal").stringValue()).isEqualTo("1180.00");
    assertThat(fields.get("subtotal").stringValue()).isEqualTo("1000.00");
    assertThat(fields.get("reverseCharge").booleanValue()).isFalse();
    assertThat(fields.get("sellerGstin").isNull()).isTrue();
    var line = fields.get("lineItems").get(0);
    assertThat(line.get("quantity").stringValue()).isEqualTo("2");
    assertThat(line.get("taxLines").get(0).get("rate").stringValue()).isEqualTo("18");
    assertThat(line.get("hsnSac").isNull()).isTrue();
  }

  @Test
  void of_blankOptionalText_isNull() {
    var e = extraction(lines(line(1, "Example widget")));
    var blankSeller =
        new InvoiceExtraction(
            e.documentClass(),
            e.invoiceNumber(),
            e.invoiceDate(),
            e.orderNumber(),
            e.orderDate(),
            Field.llm("  ", 0.9),
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
            e.grandTotal(),
            e.amountInWords(),
            e.originalInvoiceRef(),
            e.lineItems());

    ObjectNode label = ExportLabel.of(reviewed(blankSeller));

    assertThat(label.get("fields").get("sellerName").isNull()).isTrue();
    assertThat(LabelValidator.validate(label, SHA + ".json")).isEmpty();
  }

  @Test
  void of_blankDescription_isNotExportable_namingThePathOnly() {
    var reviewed = reviewed(extraction(lines(line(1, " "))));

    assertThatThrownBy(() -> ExportLabel.of(reviewed))
        .isInstanceOf(ExportLabel.NotExportable.class)
        .hasMessage("$.fields.lineItems[0].description: must be non-blank text");
  }

  @Test
  void of_repeatedLineNumber_isNotExportable() {
    var reviewed = reviewed(extraction(lines(line(1, "A"), line(1, "B"))));

    assertThatThrownBy(() -> ExportLabel.of(reviewed))
        .isInstanceOf(ExportLabel.NotExportable.class)
        .hasMessageStartingWith("$.fields.lineItems[1].lineNo:");
  }

  @ParameterizedTest
  @CsvSource({"1180, 1180.00", "1180.5, 1180.50", "1180.500, 1180.50", "-12.30, -12.30"})
  void amount_writesTwoDecimals(String value, String expected) {
    assertThat(ExportLabel.amount(new BigDecimal(value), "$.x")).isEqualTo(expected);
  }

  @Test
  void amount_moreThanTwoDecimals_isNotExportable() {
    assertThatThrownBy(() -> ExportLabel.amount(new BigDecimal("1.005"), "$.fields.grandTotal"))
        .isInstanceOf(ExportLabel.NotExportable.class)
        .hasMessage("$.fields.grandTotal: has more than two decimals");
  }

  @ParameterizedTest
  @CsvSource({"18.00, 18", "1E+1, 10", "2.5, 2.5", "0.125000, 0.125"})
  void decimal_writesPlainDecimal(String value, String expected) {
    assertThat(ExportLabel.decimal(new BigDecimal(value), "$.x")).isEqualTo(expected);
  }

  @Test
  void decimal_moreThanSixDecimals_isNotExportable() {
    assertThatThrownBy(() -> ExportLabel.decimal(new BigDecimal("0.1234567"), "$.q"))
        .isInstanceOf(ExportLabel.NotExportable.class);
  }

  @Test
  void nullValues_stayNull() {
    assertThat(ExportLabel.amount(null, "$.x")).isNull();
    assertThat(ExportLabel.decimal(null, "$.x")).isNull();
  }

  private static ReviewedExtraction reviewed(InvoiceExtraction extraction) {
    return new ReviewedExtraction(DocumentId.random(), SHA, extraction);
  }

  private static List<LineItem> lines(LineItem... items) {
    return List.of(items);
  }

  private static LineItem line(int lineNo, String description) {
    return new LineItem(
        Field.llm(lineNo, 0.9),
        Field.llm(ChargeType.PRODUCT, 0.9),
        new Field<>(description, 1.0, FieldSource.HUMAN, null),
        null,
        Field.llm(new BigDecimal("500.00"), 0.9),
        Field.llm(new BigDecimal("2.000"), 0.9),
        null,
        Field.llm(new BigDecimal("1000.00"), 0.9),
        List.of(
            new TaxLine(
                Field.llm(TaxType.IGST, 0.9),
                Field.llm(new BigDecimal("18.00"), 0.9),
                Field.llm(new BigDecimal("180.00"), 0.9))),
        Field.llm(new BigDecimal("1180.00"), 0.9));
  }

  private static InvoiceExtraction extraction(List<LineItem> lines) {
    return new InvoiceExtraction(
        Field.llm(DocumentClass.TAX_INVOICE, 0.9),
        Field.llm("tok_inv_0001", 0.9),
        Field.llm(LocalDate.of(2026, 1, 15), 0.9),
        Field.llm("tok_ord_0001", 0.9),
        null,
        Field.llm("Example Seller Pvt Ltd", 0.9),
        null,
        null,
        null,
        null,
        null,
        Field.llm("Karnataka", 0.9),
        Field.llm(false, 0.9),
        Field.llm(Currency.INR, 0.9),
        Field.llm(new BigDecimal("1000"), 0.9),
        Field.llm(new BigDecimal("180.00"), 0.9),
        Field.llm(new BigDecimal("1180.00"), 0.9),
        null,
        null,
        lines);
  }
}
