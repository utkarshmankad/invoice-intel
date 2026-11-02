package dev.invoiceintel.eval.metrics.fields;

/**
 * The header fields of the extraction schema (spec §5.1) scored by per-field accuracy (EVAL-2).
 * Each field names its label key (architecture §9.2) and how its values are compared.
 */
public enum HeaderField {
  DOCUMENT_CLASS("documentClass", Kind.TEXT),
  INVOICE_NUMBER("invoiceNumber", Kind.TOKEN),
  INVOICE_DATE("invoiceDate", Kind.DATE),
  ORDER_NUMBER("orderNumber", Kind.TOKEN),
  ORDER_DATE("orderDate", Kind.DATE),
  SELLER_NAME("sellerName", Kind.TEXT),
  SELLER_GSTIN("sellerGstin", Kind.TEXT),
  SELLER_PAN("sellerPan", Kind.TEXT),
  SELLER_ADDRESS("sellerAddress", Kind.TEXT),
  PLACE_OF_SUPPLY("placeOfSupply", Kind.TEXT),
  PLACE_OF_DELIVERY("placeOfDelivery", Kind.TEXT),
  BUYER_STATE("buyerState", Kind.TEXT),
  REVERSE_CHARGE("reverseCharge", Kind.TEXT),
  CURRENCY("currency", Kind.TEXT),
  SUBTOTAL("subtotal", Kind.AMOUNT),
  TOTAL_TAX("totalTax", Kind.AMOUNT),
  GRAND_TOTAL("grandTotal", Kind.AMOUNT),
  AMOUNT_IN_WORDS("amountInWords", Kind.TEXT),
  // A credit note's link to the original invoice holds an invoice number, which the PII stage
  // pseudonymizes like any other (spec §6.1).
  ORIGINAL_INVOICE_REF("originalInvoiceRef", Kind.TOKEN);

  /** How two values of a field are compared (architecture §6.9). */
  public enum Kind {
    /** Trimmed, whitespace collapsed, case ignored. */
    TEXT,
    /**
     * Pseudonymized identifiers: the caller tokenizes the label value with the run's keyset first
     * (§9.2); compared exactly after trimming.
     */
    TOKEN,
    /** ISO {@code YYYY-MM-DD} dates compared as dates; unparseable values fall back to TEXT. */
    DATE,
    /** Decimal amounts compared by value, ignoring scale; unparseable values fall back to TEXT. */
    AMOUNT
  }

  private final String labelKey;
  private final Kind kind;

  HeaderField(String labelKey, Kind kind) {
    this.labelKey = labelKey;
    this.kind = kind;
  }

  /** The key of this field in a label file's {@code fields} object. */
  public String labelKey() {
    return labelKey;
  }

  public Kind kind() {
    return kind;
  }
}
