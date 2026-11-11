package dev.invoiceintel.eval.metrics.fields;

import static org.assertj.core.api.Assertions.assertThat;

import dev.invoiceintel.common.extraction.InvoiceExtraction;
import dev.invoiceintel.eval.run.LabelExtractions;
import dev.invoiceintel.eval.run.SyntheticDataset;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class DocumentValuesMapperTest {

  private static final JsonNode TAX_INVOICE = load(SyntheticDataset.TAX_INVOICE);

  @Test
  void fromLabel_readsHeaderAndLineItems() {
    DocumentValues values = DocumentValuesMapper.fromLabel(TAX_INVOICE, UnaryOperator.identity());

    assertThat(values.value(HeaderField.DOCUMENT_CLASS)).isEqualTo("TAX_INVOICE");
    assertThat(values.value(HeaderField.INVOICE_DATE)).isEqualTo("2024-03-15");
    assertThat(values.value(HeaderField.REVERSE_CHARGE)).isEqualTo("false");
    assertThat(values.value(HeaderField.GRAND_TOTAL)).isEqualTo("1227.20");
    assertThat(values.value(HeaderField.SELLER_PAN)).isNull();
    assertThat(values.lineItems())
        .extracting(DocumentValues.LineItem::lineTotal)
        .containsExactly(new BigDecimal("1180.00"), new BigDecimal("47.20"));
  }

  @Test
  void fromLabel_tokenizesTokenFieldsOnly() {
    DocumentValues values = DocumentValuesMapper.fromLabel(TAX_INVOICE, v -> "T(" + v + ")");

    assertThat(values.value(HeaderField.INVOICE_NUMBER)).startsWith("T(");
    assertThat(values.value(HeaderField.ORDER_NUMBER)).startsWith("T(");
    assertThat(values.value(HeaderField.ORIGINAL_INVOICE_REF)).isNull();
    assertThat(values.value(HeaderField.SELLER_NAME)).doesNotStartWith("T(");
  }

  @Test
  void fromExtraction_ofExactExtraction_matchesLabel() {
    InvoiceExtraction extraction = LabelExtractions.from(TAX_INVOICE);

    FieldMetrics.DocumentScore score =
        FieldMetrics.scoreDocument(
            new FieldMetrics.DocumentPair(
                "doc",
                DocumentValuesMapper.fromLabel(TAX_INVOICE, UnaryOperator.identity()),
                DocumentValuesMapper.fromExtraction(extraction)));

    assertThat(score.headerCorrect()).isTrue();
    assertThat(score.lineItems()).isEqualTo(new LineItemCounts(2, 0, 0));
  }

  @Test
  void fromExtraction_missingFieldsAndLines_areNull() {
    InvoiceExtraction empty =
        new InvoiceExtraction(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null);

    DocumentValues values = DocumentValuesMapper.fromExtraction(empty);

    assertThat(values.header().values()).containsOnlyNulls();
    assertThat(values.lineItems()).isEqualTo(List.of());
  }

  private static JsonNode load(String sha) {
    try (InputStream in =
        DocumentValuesMapperTest.class.getResourceAsStream("/fixtures/labels/" + sha + ".json")) {
      return JsonMapper.builder().build().readTree(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
