package dev.invoiceintel.eval.metrics.pii;

/**
 * The names of {@code dev.invoiceintel.pii.PiiType} (specs §6.1), which {@code eval} can't import
 * (architecture.md §4.1). Label spans and stored {@code pii_span} rows both use these names.
 */
public enum PiiSpanType {
  PERSON,
  ADDRESS,
  PIN_CODE,
  PHONE,
  EMAIL,
  PAN,
  AADHAAR,
  GSTIN,
  ORDER_NUMBER,
  INVOICE_NUMBER
}
