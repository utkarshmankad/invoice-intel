package dev.invoiceintel.validate;

import dev.invoiceintel.common.checksum.GstinChecksum;
import dev.invoiceintel.common.extraction.ChargeType;
import dev.invoiceintel.common.extraction.Field;
import dev.invoiceintel.common.extraction.InvoiceExtraction;
import dev.invoiceintel.common.extraction.LineItem;
import dev.invoiceintel.common.extraction.TaxLine;
import dev.invoiceintel.common.extraction.TaxType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Builds synthetic extractions with the GST fields VAL-4 and VAL-5 read, starting from {@link
 * ExtractionFixtures#consistentTaxInvoice()}. GSTINs are generated from a made-up PAN.
 */
final class GstExtractions {

  private GstExtractions() {}

  /** A valid synthetic GSTIN for {@code stateCode}. */
  static String gstin(String stateCode) {
    String body = stateCode + "ABCDE1234F1Z";
    return body + GstinChecksum.checkChar(body);
  }

  static Builder builder() {
    return new Builder();
  }

  static final class Builder {
    private final InvoiceExtraction base = ExtractionFixtures.consistentTaxInvoice();
    private String sellerGstin;
    private String sellerAddress;
    private String placeOfSupply;
    private LocalDate invoiceDate = base.invoiceDate().value();
    private List<List<TaxType>> lines = List.of(List.of(TaxType.CGST, TaxType.SGST));

    Builder sellerGstin(String value) {
      sellerGstin = value;
      return this;
    }

    Builder sellerAddress(String value) {
      sellerAddress = value;
      return this;
    }

    Builder placeOfSupply(String value) {
      placeOfSupply = value;
      return this;
    }

    Builder invoiceDate(LocalDate value) {
      invoiceDate = value;
      return this;
    }

    /** One line item per argument, each with tax lines of the given types. */
    @SafeVarargs
    @SuppressWarnings("varargs") // the array is only copied, never written
    final Builder lines(List<TaxType>... taxTypesPerLine) {
      lines = List.copyOf(Arrays.asList(taxTypesPerLine));
      return this;
    }

    InvoiceExtraction build() {
      var items = new ArrayList<LineItem>();
      for (int i = 0; i < lines.size(); i++) {
        items.add(line(i + 1, lines.get(i)));
      }
      return new InvoiceExtraction(
          base.documentClass(),
          base.invoiceNumber(),
          Field.llm(invoiceDate, 0.9),
          base.orderNumber(),
          base.orderDate(),
          base.sellerName(),
          field(sellerGstin),
          base.sellerPan(),
          field(sellerAddress),
          field(placeOfSupply),
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

    private static Field<String> field(String value) {
      return value == null ? null : Field.llm(value, 0.9);
    }

    private static LineItem line(int lineNo, List<TaxType> types) {
      Field<BigDecimal> amount = Field.llm(new BigDecimal("100.00"), 0.9);
      List<TaxLine> taxes =
          types.stream()
              .map(t -> new TaxLine(Field.llm(t, 0.9), Field.llm(new BigDecimal("9"), 0.9), amount))
              .toList();
      return new LineItem(
          Field.llm(lineNo, 1.0),
          Field.llm(ChargeType.PRODUCT, 0.9),
          Field.llm("Synthetic item", 0.9),
          null,
          amount,
          Field.llm(BigDecimal.ONE, 0.9),
          null,
          amount,
          taxes,
          amount);
    }
  }
}
