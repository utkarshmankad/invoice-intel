package dev.invoiceintel.common.fixtures;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Ground truth of a generated document: the values the PDF prints, so tests can compare extraction,
 * validation and redaction results against them.
 *
 * @param originalInvoiceNumber invoice a credit note refers to; {@code null} for other types
 */
public record SyntheticInvoice(
    InvoiceType type,
    String invoiceNumber,
    LocalDate invoiceDate,
    String orderNumber,
    LocalDate orderDate,
    String originalInvoiceNumber,
    Seller seller,
    Buyer buyer,
    TaxType taxType,
    List<LineItem> lineItems,
    BigDecimal netTotal,
    BigDecimal taxTotal,
    BigDecimal grandTotal,
    String amountInWords) {

  public SyntheticInvoice {
    lineItems = List.copyOf(lineItems);
  }

  public Optional<String> creditedInvoiceNumber() {
    return Optional.ofNullable(originalInvoiceNumber);
  }

  /** Buyer PII plus the real-looking order and document numbers (pii.md). */
  public List<String> piiValues() {
    List<String> values = new ArrayList<>(buyer.piiValues());
    values.add(orderNumber);
    values.add(invoiceNumber);
    creditedInvoiceNumber().ifPresent(values::add);
    return List.copyOf(values);
  }

  /** How tax is levied: split CGST + SGST within a state, IGST across states; VAT/CST pre-GST. */
  public enum TaxType {
    CGST_SGST,
    IGST,
    VAT,
    CST
  }
}
