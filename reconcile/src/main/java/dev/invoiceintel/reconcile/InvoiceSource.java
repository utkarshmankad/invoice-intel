package dev.invoiceintel.reconcile;

import java.util.List;

/**
 * The invoices a reconciliation run reads (REC-2, architecture.md §6.7, ADR-26). {@code pipeline}
 * implements it from each document's current extraction, since {@code reconcile} can't depend on
 * {@code extract} (§4.1).
 */
@FunctionalInterface
public interface InvoiceSource {

  /** One entry per document to reconcile, pseudonymized. */
  List<ReconciliationInvoice> currentInvoices();
}
