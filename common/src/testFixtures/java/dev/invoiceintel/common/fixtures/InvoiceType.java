package dev.invoiceintel.common.fixtures;

/** Document types the generator can produce. */
public enum InvoiceType {
  /** GST tax invoice, dated on or after 2017-07-01. */
  GST_TAX_INVOICE,
  /** GST credit note referencing an earlier tax invoice. */
  CREDIT_NOTE,
  /** VAT/CST invoice dated before GST came into force on 2017-07-01. */
  PRE_GST_INVOICE
}
