package dev.invoiceintel.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import dev.invoiceintel.app.ui.ReviewDocumentView.Box;
import dev.invoiceintel.app.ui.ReviewDocumentView.FieldRow;
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

/** REV-2: the document page's field rows and evidence boxes (architecture.md §6.8). */
class ReviewDocumentViewTest {

  @Test
  void rows_nullExtraction_isEmpty() {
    assertThat(ReviewDocumentView.rows(null)).isEmpty();
  }

  @Test
  void rows_listFieldsInSchemaOrderWithPathsAndSkipAbsentOnes() {
    var rows = ReviewDocumentView.rows(extraction());

    assertThat(rows)
        .extracting(FieldRow::path)
        .containsExactly(
            "documentClass",
            "invoiceNumber",
            "invoiceDate",
            "orderNumber",
            "sellerName",
            "buyerState",
            "currency",
            "subtotal",
            "totalTax",
            "grandTotal",
            "lineItems[0].lineNo",
            "lineItems[0].chargeType",
            "lineItems[0].description",
            "lineItems[0].unitPrice",
            "lineItems[0].quantity",
            "lineItems[0].netAmount",
            "lineItems[0].taxLines[0].type",
            "lineItems[0].taxLines[0].rate",
            "lineItems[0].taxLines[0].amount",
            "lineItems[0].lineTotal");
  }

  @Test
  void rows_formatValuesConfidenceSourceAndHtmlId() {
    var rows = ReviewDocumentView.rows(extraction());

    assertThat(row(rows, "grandTotal"))
        .isEqualTo(
            new FieldRow(
                "grandTotal",
                "field-grandTotal",
                "1180.00",
                "0.95",
                "LLM",
                new Box(1, "grandTotal", "400", "700.5", "80.25", "12")));
    assertThat(row(rows, "invoiceDate").value()).isEqualTo("2026-01-15");
    assertThat(row(rows, "documentClass").value()).isEqualTo("TAX_INVOICE");
    assertThat(row(rows, "lineItems[0].taxLines[0].rate").htmlId())
        .isEqualTo("field-lineItems-0-taxLines-0-rate");
    assertThat(row(rows, "sellerName").source()).isEqualTo("RULE");
  }

  @Test
  void rows_fieldWithoutEvidenceBox_hasNoBox() {
    var rows = ReviewDocumentView.rows(extraction());

    // Evidence that cites lines only (no mapped box) and no evidence at all both have no box.
    assertThat(row(rows, "invoiceNumber").hasBox()).isFalse();
    assertThat(row(rows, "subtotal").hasBox()).isFalse();
  }

  @Test
  void boxes_returnOnlyThoseOnThePage() {
    var rows = ReviewDocumentView.rows(extraction());

    assertThat(ReviewDocumentView.boxes(rows, 1))
        .extracting(Box::path)
        .containsExactly("grandTotal");
    assertThat(ReviewDocumentView.boxes(rows, 2))
        .extracting(Box::path)
        .containsExactly("lineItems[0].description");
    assertThat(ReviewDocumentView.boxes(rows, 3)).isEmpty();
  }

  @ParameterizedTest
  @CsvSource({"12.0, 12", "0.5, 0.5", "595.276, 595.276", "1.0E-4, 0.0001", "2480, 2480"})
  void svgNumber_isPlainWithoutTrailingZeros(double value, String expected) {
    assertThat(ReviewDocumentView.svgNumber(value)).isEqualTo(expected);
  }

  private static FieldRow row(List<FieldRow> rows, String path) {
    return rows.stream().filter(r -> r.path().equals(path)).findFirst().orElseThrow();
  }

  private static InvoiceExtraction extraction() {
    var line =
        new LineItem(
            Field.llm(1, 0.9),
            Field.llm(ChargeType.PRODUCT, 0.9),
            new Field<>(
                "Example widget",
                0.8,
                FieldSource.LLM,
                new Evidence(2, new BoundingBox(10, 20, 30, 40), List.of("p2:l3"))),
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
        new Field<>("TEST-INV-0001", 0.9, FieldSource.LLM, new Evidence(1, null, List.of("p1:l2"))),
        Field.llm(LocalDate.of(2026, 1, 15), 0.9),
        Field.llm("TEST-ORDER-0001", 0.9),
        null,
        new Field<>("Example Seller Pvt Ltd", 0.99, FieldSource.RULE, null),
        null,
        null,
        null,
        null,
        null,
        Field.llm("Karnataka", 0.9),
        null,
        Field.llm(Currency.INR, 0.9),
        Field.llm(new BigDecimal("1000.00"), 0.9),
        Field.llm(new BigDecimal("180.00"), 0.9),
        new Field<>(
            new BigDecimal("1180.00"),
            0.95,
            FieldSource.LLM,
            new Evidence(1, new BoundingBox(400, 700.5, 80.25, 12), List.of("p1:l20"))),
        null,
        null,
        List.of(line));
  }
}
