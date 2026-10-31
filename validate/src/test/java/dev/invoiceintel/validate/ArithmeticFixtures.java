package dev.invoiceintel.validate;

import dev.invoiceintel.common.DocumentClass;
import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.common.extraction.ChargeType;
import dev.invoiceintel.common.extraction.Field;
import dev.invoiceintel.common.extraction.InvoiceExtraction;
import dev.invoiceintel.common.extraction.LineItem;
import dev.invoiceintel.common.extraction.TaxLine;
import dev.invoiceintel.common.extraction.TaxType;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * Builders for synthetic extractions with chosen amounts, for the arithmetic rules VAL-1..VAL-3
 * (testing.md). Starts from {@link ExtractionFixtures#consistentTaxInvoice()} and replaces only the
 * amounts and lines under test.
 */
final class ArithmeticFixtures {

  static final ValidationContext CONTEXT =
      new ValidationContext(DocumentId.random(), DocumentClass.TAX_INVOICE);

  private ArithmeticFixtures() {}

  /** {@code null} gives an absent field. */
  static Field<BigDecimal> money(String amount) {
    return amount == null ? null : Field.llm(new BigDecimal(amount), 0.9);
  }

  static TaxLine tax(TaxType type, String rate, String amount) {
    return new TaxLine(
        type == null ? null : Field.llm(type, 0.9),
        rate == null ? null : Field.llm(new BigDecimal(rate), 0.9),
        money(amount));
  }

  static LineItem line(Integer lineNo, ChargeType chargeType, String netAmount, TaxLine... taxes) {
    return new LineItem(
        lineNo == null ? null : Field.llm(lineNo, 1.0),
        Field.llm(chargeType, 0.9),
        Field.llm("Synthetic item", 0.9),
        null,
        money(netAmount),
        Field.llm(BigDecimal.ONE, 0.9),
        null,
        money(netAmount),
        Arrays.asList(taxes),
        money(netAmount));
  }

  static LineItem line(int lineNo, String netAmount, TaxLine... taxes) {
    return line(lineNo, ChargeType.PRODUCT, netAmount, taxes);
  }

  static InvoiceExtraction withLines(String subtotal, List<LineItem> lines) {
    return with(subtotal, "0", subtotal, lines);
  }

  static InvoiceExtraction withTotals(String subtotal, String totalTax, String grandTotal) {
    return with(
        subtotal, totalTax, grandTotal, ExtractionFixtures.consistentTaxInvoice().lineItems());
  }

  static InvoiceExtraction with(
      String subtotal, String totalTax, String grandTotal, List<LineItem> lines) {
    InvoiceExtraction x = ExtractionFixtures.consistentTaxInvoice();
    return new InvoiceExtraction(
        x.documentClass(),
        x.invoiceNumber(),
        x.invoiceDate(),
        x.orderNumber(),
        x.orderDate(),
        x.sellerName(),
        x.sellerGstin(),
        x.sellerPan(),
        x.sellerAddress(),
        x.placeOfSupply(),
        x.placeOfDelivery(),
        x.buyerState(),
        x.reverseCharge(),
        x.currency(),
        money(subtotal),
        money(totalTax),
        money(grandTotal),
        x.amountInWords(),
        x.originalInvoiceRef(),
        lines);
  }
}
