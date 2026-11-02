package dev.invoiceintel.eval.metrics.fields;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.invoiceintel.eval.metrics.fields.DocumentValues.LineItem;
import dev.invoiceintel.eval.metrics.fields.FieldMetrics.DocumentPair;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Hand-made synthetic label/extraction pairs; no real invoice values. */
class FieldMetricsTest {

  private static final int FIELDS = HeaderField.values().length;

  /** A synthetic labelled tax invoice; unlisted fields are null (not shown on the document). */
  private static Map<HeaderField, String> taxInvoiceLabel() {
    Map<HeaderField, String> h = new EnumMap<>(HeaderField.class);
    h.put(HeaderField.DOCUMENT_CLASS, "TAX_INVOICE");
    h.put(HeaderField.INVOICE_NUMBER, "INVOICE_NUMBER_tok1");
    h.put(HeaderField.INVOICE_DATE, "2024-03-15");
    h.put(HeaderField.ORDER_NUMBER, "ORDER_NUMBER_tok2");
    h.put(HeaderField.SELLER_NAME, "Example Home Store");
    h.put(HeaderField.BUYER_STATE, "Maharashtra");
    h.put(HeaderField.CURRENCY, "INR");
    h.put(HeaderField.SUBTOTAL, "1040.00");
    h.put(HeaderField.TOTAL_TAX, "187.20");
    h.put(HeaderField.GRAND_TOTAL, "1227.20");
    return h;
  }

  private static List<LineItem> taxInvoiceLines() {
    return List.of(
        new LineItem("Steel water bottle 1L", new BigDecimal("1180.00")),
        new LineItem("Shipping charges", new BigDecimal("47.20")));
  }

  private static DocumentPair pair(
      String id, Map<HeaderField, String> actualHeader, List<LineItem> actualLines) {
    return new DocumentPair(
        id,
        new DocumentValues(taxInvoiceLabel(), taxInvoiceLines()),
        new DocumentValues(actualHeader, actualLines));
  }

  @Test
  void score_perfectExtraction_allCorrect() {
    Map<HeaderField, String> actual = new HashMap<>(taxInvoiceLabel());
    actual.put(HeaderField.SELLER_NAME, "  EXAMPLE   home store ");
    actual.put(HeaderField.GRAND_TOTAL, "1227.2");

    var result = FieldMetrics.score(List.of(pair("doc-1", actual, taxInvoiceLines())));

    assertThat(result.header()).isEqualTo(new FieldCounts(FIELDS, FIELDS));
    assertThat(result.lineItems()).isEqualTo(new LineItemCounts(2, 0, 0));
    assertThat(result.lineItems().f1()).isEqualTo(1.0);
    assertThat(result.documents())
        .singleElement()
        .satisfies(
            d -> {
              assertThat(d.documentId()).isEqualTo("doc-1");
              assertThat(d.headerCorrect()).isTrue();
            });
  }

  @Test
  void score_nullLabelWithExtractedValue_isMismatch() {
    Map<HeaderField, String> actual = new HashMap<>(taxInvoiceLabel());
    actual.put(HeaderField.SELLER_PAN, "seller-pan-placeholder");

    var score = FieldMetrics.scoreDocument(pair("doc-1", actual, taxInvoiceLines()));

    assertThat(score.mismatchedFields()).containsExactly(HeaderField.SELLER_PAN);
    assertThat(score.headerCorrect()).isFalse();
  }

  @Test
  void score_missingAndWrongValues_listedPerDocument() {
    Map<HeaderField, String> actual = new HashMap<>(taxInvoiceLabel());
    actual.remove(HeaderField.INVOICE_DATE);
    actual.put(HeaderField.TOTAL_TAX, "180.00");
    actual.put(HeaderField.ORDER_NUMBER, "ORDER_NUMBER_other");

    var score = FieldMetrics.scoreDocument(pair("doc-1", actual, taxInvoiceLines()));

    assertThat(score.mismatchedFields())
        .containsExactlyInAnyOrder(
            HeaderField.INVOICE_DATE, HeaderField.TOTAL_TAX, HeaderField.ORDER_NUMBER);
  }

  @Test
  void score_aggregatesPerFieldAndMicroAveragesLineItems() {
    // doc-1: perfect. doc-2: wrong grand total, one line missed, one extra line.
    Map<HeaderField, String> wrongTotal = new HashMap<>(taxInvoiceLabel());
    wrongTotal.put(HeaderField.GRAND_TOTAL, "1272.20");
    List<LineItem> doc2Lines =
        List.of(
            new LineItem("steel water bottle", new BigDecimal("1180.00")),
            new LineItem("Gift wrap", new BigDecimal("25.00")));

    var result =
        FieldMetrics.score(
            List.of(
                pair("doc-1", taxInvoiceLabel(), taxInvoiceLines()),
                pair("doc-2", wrongTotal, doc2Lines)));

    assertThat(result.perField().get(HeaderField.GRAND_TOTAL)).isEqualTo(new FieldCounts(1, 2));
    assertThat(result.perField().get(HeaderField.GRAND_TOTAL).accuracy()).isEqualTo(0.5);
    assertThat(result.perField().get(HeaderField.SELLER_NAME)).isEqualTo(new FieldCounts(2, 2));
    assertThat(result.perField()).hasSize(FIELDS);
    assertThat(result.header()).isEqualTo(new FieldCounts(2 * FIELDS - 1, 2 * FIELDS));
    assertThat(result.lineItems()).isEqualTo(new LineItemCounts(3, 1, 1));
    assertThat(result.lineItems().f1()).isCloseTo(0.75, within(1e-9));
    assertThat(result.documents())
        .extracting(FieldMetrics.DocumentScore::headerCorrect)
        .containsExactly(true, false);
  }

  @Test
  void score_emptyDataset_zeroCounts() {
    var result = FieldMetrics.score(List.of());

    assertThat(result.header()).isEqualTo(FieldCounts.ZERO);
    assertThat(result.perField().values()).containsOnly(FieldCounts.ZERO);
    assertThat(result.lineItems()).isEqualTo(LineItemCounts.ZERO);
    assertThat(result.documents()).isEmpty();
  }

  @Test
  void documentValues_toStringHidesValues() {
    var values = new DocumentValues(taxInvoiceLabel(), taxInvoiceLines());

    assertThat(values.toString())
        .isEqualTo("DocumentValues[10 header values, 2 lines]")
        .doesNotContain("Example");
    assertThat(new DocumentValues(null, null).lineItems()).isEmpty();
  }
}
