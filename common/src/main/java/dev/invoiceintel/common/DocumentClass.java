package dev.invoiceintel.common;

/**
 * Kind of invoice document (specs.md §4.1), the {@code document.document_class} column. The Text
 * stage sets a rule-based hint (TXT-4); the extraction LLM returns its own, and a mismatch lowers
 * confidence (VAL-8).
 */
public enum DocumentClass {
  /** GST tax invoice, dated on or after 2017-07-01. */
  TAX_INVOICE,
  /** GST credit note against an earlier invoice. */
  CREDIT_NOTE,
  /** GST bill of supply (exempt goods or composition dealer, no tax charged). */
  BILL_OF_SUPPLY,
  /** VAT/CST invoice dated before GST came into force. */
  PRE_GST_INVOICE,
  /** Anything else, or not enough evidence to tell. */
  OTHER
}
