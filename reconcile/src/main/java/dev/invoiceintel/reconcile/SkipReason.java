package dev.invoiceintel.reconcile;

/** Why an order-history row was not imported. Reported as counts only (PII-8). */
public enum SkipReason {
  /** The row has a different number of fields than the header. */
  WRONG_FIELD_COUNT,
  /** The order ID column is empty. */
  MISSING_ORDER_ID,
  /** The order date is neither an ISO-8601 date nor date-time. */
  BAD_DATE,
  /** The quantity is not a whole number. */
  BAD_QUANTITY,
  /** A price or total is not a decimal amount. */
  BAD_AMOUNT
}
