package dev.invoiceintel.reconcile;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
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
        .query(OrderHistoryStore::item)
        .list();
  }

  /**
   * The rows the matcher reads (REC-2, architecture.md §6.7): for each order token, the rows of the
   * latest import that has it, so importing the same export again replaces its rows instead of
   * doubling them, while orders only an earlier import (or another export layout) has stay. In no
   * particular order.
   */
  public List<OrderHistoryItem> findCurrent() {
    return jdbc.sql(
            """
            SELECT o.order_token, o.order_date, o.product_name, o.asin, o.quantity, o.unit_price,
                   o.item_total
            FROM order_history_item o
            WHERE o.import_id = (
              SELECT l.import_id FROM order_history_item l
              WHERE l.order_token = o.order_token
              ORDER BY l.created_at DESC, l.import_id DESC
              LIMIT 1)
            """)
        .query(OrderHistoryStore::item)
        .list();
  }

  private static OrderHistoryItem item(ResultSet rs, int row) throws SQLException {
    return new OrderHistoryItem(
        rs.getString("order_token"),
        rs.getObject("order_date", LocalDate.class),
        rs.getString("product_name"),
        rs.getString("asin"),
        rs.getObject("quantity", Integer.class),
        rs.getBigDecimal("unit_price"),
        rs.getBigDecimal("item_total"));
  }
}
