package dev.invoiceintel.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.invoiceintel.common.DocumentClass;
import dev.invoiceintel.common.extraction.BoundingBox;
import dev.invoiceintel.common.extraction.ChargeType;
import dev.invoiceintel.common.extraction.Currency;
import dev.invoiceintel.common.extraction.Evidence;
import dev.invoiceintel.common.extraction.Field;
import dev.invoiceintel.common.extraction.FieldSource;
import dev.invoiceintel.common.extraction.InvoiceExtraction;
import dev.invoiceintel.common.extraction.LineItem;
import dev.invoiceintel.common.extraction.TaxLine;
import dev.invoiceintel.common.extraction.TaxType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** REV-3, ADR-30: applying reviewer corrections by validator field path. */
class FieldCorrectionsTest {

  private static final Evidence TOTAL_BOX =
      new Evidence(1, new BoundingBox(400, 700, 80, 12), List.of("p1:l20"));

  @ParameterizedTest
  @CsvSource({
    "grandTotal, 1180.00",
    "invoiceDate, 2026-01-15",
    "currency, INR",
    "reverseCharge, false",
    "lineItems[0].lineNo, 1",
    "lineItems[0].taxLines[0].rate, 18",
    "lineItems[0].taxLines[0].type, IGST",
    "documentClass, TAX_INVOICE"
  })
  void value_existingField_isFormText(String path, String expected) {
    assertThat(FieldCorrections.value(extraction(), path)).contains(expected);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"orderDate", "lineItems[1].lineNo", "lineItems", "grandTotal.value", "x["})
  void value_absentOrNotAField_isEmpty(String path) {
    assertThat(FieldCorrections.value(extraction(), path)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "a.", ".a", "a[]", "a[1234]", "a[x]", "a1", "a[1]b", "a..b", "a[1"})
  void wellFormed_malformedPath_isFalse(String path) {
    assertThat(FieldCorrections.wellFormed(path)).isFalse();
  }

  @Test
  void wellFormed_longPath_isFalse() {
    assertThat(FieldCorrections.wellFormed("a".repeat(101))).isFalse();
    assertThat(FieldCorrections.wellFormed("lineItems[12].taxLines[0].rate")).isTrue();
  }

  @Test
  void apply_amount_setsHumanSourceFullConfidenceAndKeepsEvidence() {
    var corrected = FieldCorrections.apply(extraction(), "grandTotal", " 1190.50 ");

    assertThat(corrected.grandTotal().value()).isEqualByComparingTo("1190.50");
    assertThat(corrected.grandTotal().source()).isEqualTo(FieldSource.HUMAN);
    assertThat(corrected.grandTotal().confidence()).isEqualTo(1.0);
    assertThat(corrected.grandTotal().evidence()).isEqualTo(TOTAL_BOX);
    assertThat(corrected.subtotal()).isEqualTo(extraction().subtotal());
  }

  @Test
  void apply_nestedTypedFields_parsesEachType() {
    var e = extraction();
    e = FieldCorrections.apply(e, "lineItems[0].taxLines[0].type", "CGST");
    e = FieldCorrections.apply(e, "lineItems[0].lineNo", "2");
    e = FieldCorrections.apply(e, "invoiceDate", "2026-02-01");
    e = FieldCorrections.apply(e, "reverseCharge", "true");
    e = FieldCorrections.apply(e, "sellerName", "Other Seller");

    var tax = e.lineItems().getFirst().taxLines().getFirst();
    assertThat(tax.type().value()).isEqualTo(TaxType.CGST);
    assertThat(e.lineItems().getFirst().lineNo().value()).isEqualTo(2);
    assertThat(e.invoiceDate().value()).isEqualTo(LocalDate.of(2026, 2, 1));
    assertThat(e.reverseCharge().value()).isTrue();
    assertThat(e.sellerName().value()).isEqualTo("Other Seller");
  }

  @ParameterizedTest
  @CsvSource({
    "grandTotal, abc",
    "lineItems[0].lineNo, 1.5",
    "invoiceDate, 15/01/2026",
    "currency, XYZ",
    "reverseCharge, yes",
    "grandTotal, ' '",
    "invoiceNumber, TEST-INV-0002",
    "orderNumber, TEST-ORDER-0002",
    "orderDate, 2026-01-01",
    "noSuchField, 1",
    "lineItems[5].lineNo, 1"
  })
  void apply_invalid_throwsWithoutValueInMessage(String path, String value) {
    assertThatThrownBy(() -> FieldCorrections.apply(extraction(), path, value))
        .isInstanceOf(FieldCorrections.InvalidCorrectionException.class)
        .satisfies(
            e -> assertThat(e.getMessage()).doesNotContain(value.strip().isEmpty() ? "\0" : value));
  }

  @Test
  void apply_tooLongOrNullValue_throws() {
    assertThatThrownBy(() -> FieldCorrections.apply(extraction(), "sellerName", "x".repeat(501)))
        .isInstanceOf(FieldCorrections.InvalidCorrectionException.class);
    assertThatThrownBy(() -> FieldCorrections.apply(extraction(), "sellerName", null))
        .isInstanceOf(FieldCorrections.InvalidCorrectionException.class);
    assertThatThrownBy(() -> FieldCorrections.apply(extraction(), null, "x"))
        .isInstanceOf(FieldCorrections.InvalidCorrectionException.class);
  }

  private static InvoiceExtraction extraction() {
    var line =
        new LineItem(
            Field.llm(1, 0.9),
            Field.llm(ChargeType.PRODUCT, 0.9),
            Field.llm("Example widget", 0.8),
            null,
            Field.llm(new BigDecimal("1000.00"), 0.9),
            Field.llm(new BigDecimal("1"), 0.9),
            null,
            Field.llm(new BigDecimal("1000.00"), 0.9),
            List.of(
                new TaxLine(
                    Field.llm(TaxType.IGST, 0.9),
                    Field.llm(new BigDecimal("18"), 0.9),
                    Field.llm(new BigDecimal("180.00"), 0.9))),
            Field.llm(new BigDecimal("1180.00"), 0.9));
    return new InvoiceExtraction(
        Field.llm(DocumentClass.TAX_INVOICE, 0.9),
        Field.llm("TEST-INV-0001", 0.9),
        Field.llm(LocalDate.of(2026, 1, 15), 0.9),
        Field.llm("TEST-ORDER-0001", 0.9),
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
        Field.llm(new BigDecimal("1000.00"), 0.9),
        Field.llm(new BigDecimal("180.00"), 0.9),
        new Field<>(new BigDecimal("1180.00"), 0.95, FieldSource.LLM, TOTAL_BOX),
        null,
        null,
        List.of(line));
  }
}
