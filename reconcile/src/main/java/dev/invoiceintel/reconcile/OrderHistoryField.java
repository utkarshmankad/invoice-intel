package dev.invoiceintel.reconcile;

import java.util.Arrays;
import java.util.Optional;

/**
 * The {@code order_history_item} columns a CSV column can be imported into (REC-1, architecture.md
 * §7). {@link #target()} is the name used as {@code target} in {@code
 * config/order-history-mapping.yml}.
 */
public enum OrderHistoryField {
  ORDER_TOKEN("order_token"),
  ORDER_DATE("order_date"),
  PRODUCT_NAME("product_name"),
  ASIN("asin"),
  QUANTITY("quantity"),
  UNIT_PRICE("unit_price"),
  ITEM_TOTAL("item_total");

  private final String target;

  OrderHistoryField(String target) {
    this.target = target;
  }

  public String target() {
    return target;
  }

  /** The field named {@code target} in the mapping file, if any. */
  public static Optional<OrderHistoryField> fromTarget(String target) {
    return Arrays.stream(values()).filter(f -> f.target.equals(target)).findFirst();
  }
}
