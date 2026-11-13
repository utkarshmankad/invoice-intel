package dev.invoiceintel.reconcile;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * How far invoices may differ from their order and still match (REC-2, architecture.md §6.7).
 *
 * @param amount the largest absolute difference between the invoice and order totals, inclusive
 * @param days the most days an invoice date may lie before or after the order date, inclusive
 */
public record ReconciliationTolerance(BigDecimal amount, int days) {

  /** ±₹1.00, as VAL-1..VAL-3 use, and 30 days for shipping and seller invoicing delays. */
  public static final ReconciliationTolerance DEFAULT =
      new ReconciliationTolerance(new BigDecimal("1.00"), 30);

  public ReconciliationTolerance {
    Objects.requireNonNull(amount, "amount");
    if (amount.signum() < 0) {
      throw new IllegalArgumentException("amount tolerance must not be negative");
    }
    if (days < 0) {
      throw new IllegalArgumentException("days tolerance must not be negative");
    }
  }
}
