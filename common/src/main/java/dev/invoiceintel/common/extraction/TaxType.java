package dev.invoiceintel.common.extraction;

/** Kind of tax on a line (specs.md §5.2). VAT and CST appear only on pre-GST invoices. */
public enum TaxType {
  CGST,
  SGST,
  IGST,
  UTGST,
  CESS,
  VAT,
  CST
}
