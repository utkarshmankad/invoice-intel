package dev.invoiceintel.reconcile;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Writes and reads {@code order_history_item} rows (REC-1, architecture.md §7, V10). Rows hold
 * order tokens, never order numbers.
 */
public final class OrderHistoryStore {

  private final JdbcClient jdbc;

  public OrderHistoryStore(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  public void insert(ImportId importId, List<OrderHistoryItem> items) {
    for (var item : items) {
      jdbc.sql(
              """
              INSERT INTO order_history_item
                (id, import_id, order_token, order_date, product_name, asin, quantity,
                 unit_price, item_total)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
              """)
          .params(
              UUID.randomUUID(),
              importId.value(),
              item.orderToken(),
              item.orderDate(),
              item.productName(),
              item.asin(),
              item.quantity(),
              item.unitPrice(),
              item.itemTotal())
          .update();
    }
  }

  /** The rows of one import, in no particular order. */
  public List<OrderHistoryItem> find(ImportId importId) {
    return jdbc.sql(
            """
            SELECT order_token, order_date, product_name, asin, quantity, unit_price, item_total
            FROM order_history_item WHERE import_id = ?
            """)
        .param(importId.value())
        .query(
            (rs, row) ->
                new OrderHistoryItem(
                    rs.getString("order_token"),
                    rs.getObject("order_date", java.time.LocalDate.class),
                    rs.getString("product_name"),
                    rs.getString("asin"),
                    rs.getObject("quantity", Integer.class),
                    rs.getBigDecimal("unit_price"),
                    rs.getBigDecimal("item_total")))
        .list();
  }
}
