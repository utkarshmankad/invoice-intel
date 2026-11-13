package dev.invoiceintel.reconcile;

import dev.invoiceintel.common.DocumentId;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Writes and reads {@code reconciliation_match} rows (REC-3, architecture.md §6.7, §7, V11): the
 * rows of the latest reconciliation run. Rows hold order tokens and document IDs, never order
 * numbers.
 */
public final class ReconciliationStore {

  private final JdbcClient jdbc;

  public ReconciliationStore(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  /** Replaces every row with {@code matches}; the caller holds the transaction. */
  public void replaceAll(List<ReconciliationMatch> matches) {
    jdbc.sql("DELETE FROM reconciliation_match").update();
    for (var match : matches) {
      jdbc.sql(
              """
              INSERT INTO reconciliation_match (id, order_token, status, invoice_ids, amount_diff)
              VALUES (?, ?, ?, CAST(? AS jsonb), ?)
              """)
          .params(
              UUID.randomUUID(),
              match.orderToken(),
              match.status().name(),
              json(match.invoiceIds()),
              match.amountDiff())
          .update();
    }
  }

  /**
   * The rows of the latest run: by order token, then the rows of invoices without an order number.
   */
  public List<ReconciliationMatch> findAll() {
    return jdbc.sql(
            """
            SELECT order_token, status, amount_diff,
                   ARRAY(SELECT jsonb_array_elements_text(invoice_ids)) AS invoice_ids
            FROM reconciliation_match
            ORDER BY order_token COLLATE "C" NULLS LAST, invoice_ids::text
            """)
        .query(ReconciliationStore::match)
        .list();
  }

  /** Document IDs are UUIDs, so their JSON needs no escaping. */
  private static String json(List<DocumentId> ids) {
    return ids.stream()
        .map(id -> "\"" + id.value() + "\"")
        .collect(Collectors.joining(",", "[", "]"));
  }

  private static ReconciliationMatch match(ResultSet rs, int row) throws SQLException {
    Array array = rs.getArray("invoice_ids");
    var ids = new ArrayList<DocumentId>();
    try {
      for (Object id : (Object[]) array.getArray()) {
        ids.add(new DocumentId(UUID.fromString((String) id)));
      }
    } finally {
      array.free();
    }
    return new ReconciliationMatch(
        rs.getString("order_token"),
        MatchStatus.valueOf(rs.getString("status")),
        ids,
        rs.getBigDecimal("amount_diff"));
  }
}
