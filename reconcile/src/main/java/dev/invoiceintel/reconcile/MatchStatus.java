package dev.invoiceintel.reconcile;

/**
 * Outcome of reconciling one order (REC-2, REC-3, architecture.md §6.7), the {@code
 * reconciliation_match.status} column.
 */
public enum MatchStatus {
  /** Invoices and order rows agree on amount (within tolerance) and date. */
  MATCHED,
  /** Invoice total minus credit notes differs from the order total by more than the tolerance. */
  AMOUNT_MISMATCH,
  /** Amounts agree, but an invoice is dated too far from the order date. */
  DATE_MISMATCH,
  /** Invoices whose order token is in no order-history row, or that have no order number. */
  INVOICE_WITHOUT_ORDER,
  /** Order-history rows with no invoice for their order token. */
  ORDER_WITHOUT_INVOICE
}
