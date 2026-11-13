package dev.invoiceintel.reconcile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Matches invoices to order-history rows by order token (REC-2, REC-4, architecture.md §6.7).
 *
 * <p>Per order token: the invoices' {@code grandTotal}s, minus the credit notes' (by magnitude, so
 * a negative or positive credit-note total both reduce the sum), are compared with the sum of the
 * order rows' totals; several invoices on one order (multi-seller) add up. A row's total is its
 * {@code item_total}, else {@code unit_price × quantity} (quantity 1 when missing). An unknown
 * amount on either side is an {@link MatchStatus#AMOUNT_MISMATCH} with no difference. When the
 * amounts agree, every invoice that isn't a credit note must be dated within the tolerance of the
 * order's earliest date, else {@link MatchStatus#DATE_MISMATCH}; credit notes follow returns and
 * aren't date-checked, and a missing date on either side skips the check.
 *
 * <p>Pure: it reads and writes nothing. Results are ordered by order token, with invoices that have
 * no order number last.
 */
public final class ReconciliationMatcher {

  private final ReconciliationTolerance tolerance;

  public ReconciliationMatcher(ReconciliationTolerance tolerance) {
    this.tolerance = Objects.requireNonNull(tolerance, "tolerance");
  }

  public List<ReconciliationMatch> match(
      List<ReconciliationInvoice> invoices, List<OrderHistoryItem> orderItems) {
    Map<String, List<ReconciliationInvoice>> invoicesByOrder = new TreeMap<>();
    List<ReconciliationInvoice> withoutOrderNumber = new ArrayList<>();
    for (var invoice : invoices) {
      if (invoice.orderToken() == null) {
        withoutOrderNumber.add(invoice);
      } else {
        invoicesByOrder.computeIfAbsent(invoice.orderToken(), _ -> new ArrayList<>()).add(invoice);
      }
    }
    Map<String, List<OrderHistoryItem>> itemsByOrder = new TreeMap<>();
    for (var item : orderItems) {
      itemsByOrder.computeIfAbsent(item.orderToken(), _ -> new ArrayList<>()).add(item);
    }

    var tokens = new TreeSet<>(invoicesByOrder.keySet());
    tokens.addAll(itemsByOrder.keySet());
    var matches = new ArrayList<ReconciliationMatch>();
    for (var token : tokens) {
      matches.add(
          matchOrder(
              token,
              invoicesByOrder.getOrDefault(token, List.of()),
              itemsByOrder.getOrDefault(token, List.of())));
    }
    withoutOrderNumber.stream()
        .sorted(Comparator.comparing(i -> i.documentId().value()))
        .forEach(
            i ->
                matches.add(
                    new ReconciliationMatch(
                        null, MatchStatus.INVOICE_WITHOUT_ORDER, List.of(i.documentId()), null)));
    return List.copyOf(matches);
  }

  private ReconciliationMatch matchOrder(
      String token, List<ReconciliationInvoice> invoices, List<OrderHistoryItem> items) {
    var invoiceIds =
        invoices.stream()
            .map(ReconciliationInvoice::documentId)
            .sorted(Comparator.comparing(id -> id.value()))
            .toList();
    if (items.isEmpty()) {
      return new ReconciliationMatch(token, MatchStatus.INVOICE_WITHOUT_ORDER, invoiceIds, null);
    }
    if (invoices.isEmpty()) {
      return new ReconciliationMatch(token, MatchStatus.ORDER_WITHOUT_INVOICE, List.of(), null);
    }
    var invoiced = invoicedTotal(invoices);
    var ordered = orderedTotal(items);
    if (invoiced == null || ordered == null) {
      return new ReconciliationMatch(token, MatchStatus.AMOUNT_MISMATCH, invoiceIds, null);
    }
    var diff = invoiced.subtract(ordered).setScale(2, RoundingMode.HALF_UP);
    if (diff.abs().compareTo(tolerance.amount()) > 0) {
      return new ReconciliationMatch(token, MatchStatus.AMOUNT_MISMATCH, invoiceIds, diff);
    }
    var status = datesAgree(invoices, items) ? MatchStatus.MATCHED : MatchStatus.DATE_MISMATCH;
    return new ReconciliationMatch(token, status, invoiceIds, diff);
  }

  /** Invoices minus credit notes, or null when a total is missing. */
  private static BigDecimal invoicedTotal(List<ReconciliationInvoice> invoices) {
    var sum = BigDecimal.ZERO;
    for (var invoice : invoices) {
      if (invoice.grandTotal() == null) {
        return null;
      }
      sum =
          invoice.isCreditNote()
              ? sum.subtract(invoice.grandTotal().abs())
              : sum.add(invoice.grandTotal());
    }
    return sum;
  }

  /** Sum of the rows' totals, or null when a row's total can't be told. */
  private static BigDecimal orderedTotal(List<OrderHistoryItem> items) {
    var sum = BigDecimal.ZERO;
    for (var item : items) {
      var total = rowTotal(item);
      if (total == null) {
        return null;
      }
      sum = sum.add(total);
    }
    return sum;
  }

  private static BigDecimal rowTotal(OrderHistoryItem item) {
    if (item.itemTotal() != null) {
      return item.itemTotal();
    }
    if (item.unitPrice() == null) {
      return null;
    }
    int quantity = item.quantity() == null ? 1 : item.quantity();
    return item.unitPrice().multiply(BigDecimal.valueOf(quantity));
  }

  private boolean datesAgree(List<ReconciliationInvoice> invoices, List<OrderHistoryItem> items) {
    var orderDate =
        items.stream()
            .map(OrderHistoryItem::orderDate)
            .filter(Objects::nonNull)
            .min(Comparator.naturalOrder());
    if (orderDate.isEmpty()) {
      return true;
    }
    return invoices.stream()
        .filter(i -> !i.isCreditNote() && i.invoiceDate() != null)
        .allMatch(i -> withinDays(i.invoiceDate(), orderDate.get()));
  }

  private boolean withinDays(LocalDate invoiceDate, LocalDate orderDate) {
    return Math.abs(ChronoUnit.DAYS.between(orderDate, invoiceDate)) <= tolerance.days();
  }
}
