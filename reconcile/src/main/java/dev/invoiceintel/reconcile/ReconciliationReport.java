package dev.invoiceintel.reconcile;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * The reconciliation report (REC-3, architecture.md §6.7, §8): rows per status, the documents in
 * them and the spec §9 match rate. Tokens, document IDs, counts and amounts only.
 *
 * <p>The <b>match rate</b> is the documents in {@link MatchStatus#MATCHED} rows over the documents
 * in every row (ADR-26): an invoice counts as matched only when its order exists and amount and
 * date agree. Credit notes count like invoices. Orders without an invoice add no documents.
 *
 * @param orders rows per status, every status present (0 when none)
 * @param documents documents in all rows
 * @param matchedDocuments documents in {@code MATCHED} rows
 * @param rows the rows, in the matcher's order
 */
public record ReconciliationReport(
    Map<MatchStatus, Integer> orders,
    int documents,
    int matchedDocuments,
    List<ReconciliationMatch> rows) {

  public ReconciliationReport {
    Objects.requireNonNull(orders, "orders");
    orders = Map.copyOf(orders);
    rows = List.copyOf(rows);
  }

  public static ReconciliationReport of(List<ReconciliationMatch> rows) {
    Map<MatchStatus, Integer> orders = new EnumMap<>(MatchStatus.class);
    for (MatchStatus status : MatchStatus.values()) {
      orders.put(status, 0);
    }
    int documents = 0;
    int matched = 0;
    for (var row : rows) {
      orders.merge(row.status(), 1, Integer::sum);
      documents += row.invoiceIds().size();
      if (row.status() == MatchStatus.MATCHED) {
        matched += row.invoiceIds().size();
      }
    }
    return new ReconciliationReport(orders, documents, matched, rows);
  }

  /** Matched documents over all documents; empty when there are none. */
  public OptionalDouble matchRate() {
    return documents == 0
        ? OptionalDouble.empty()
        : OptionalDouble.of((double) matchedDocuments / documents);
  }
}
