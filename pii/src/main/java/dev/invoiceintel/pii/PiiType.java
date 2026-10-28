package dev.invoiceintel.pii;

/** Kinds of personal data the detectors find (specs.md §6.1). */
public enum PiiType {
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
