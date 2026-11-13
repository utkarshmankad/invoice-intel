package dev.invoiceintel.reconcile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One imported order-history row (REC-1, architecture.md §7). The order number is a token; product
 * names are not PII (spec §6.1) and are kept. Every field but the token is null when its column
 * isn't mapped or is empty.
 */
public record OrderHistoryItem(
    String orderToken,
    LocalDate orderDate,
    String productName,
    String asin,
    Integer quantity,
    BigDecimal unitPrice,
    BigDecimal itemTotal) {

  public OrderHistoryItem {
    Objects.requireNonNull(orderToken, "orderToken");
  }

  /** Product names stay out of logs and exception messages (PII-8 backstop). */
  @Override
  public String toString() {
    return "OrderHistoryItem[orderToken=" + orderToken + "]";
  }
}
