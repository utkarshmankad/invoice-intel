package dev.invoiceintel.reconcile;

import dev.invoiceintel.common.DocumentId;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * One row of the reconciliation (REC-2, REC-3, architecture.md §6.7, §7 {@code
 * reconciliation_match}): one order token with its status and the documents matched to it.
 *
 * @param orderToken null only for an {@link MatchStatus#INVOICE_WITHOUT_ORDER} row of an invoice
 *     with no order number (one row per such invoice)
 * @param invoiceIds the order's invoices and credit notes, empty for {@link
 *     MatchStatus#ORDER_WITHOUT_INVOICE}
 * @param amountDiff invoices minus credit notes minus the order total, scale 2; null when one side
 *     is missing or an amount on either side is unknown
 */
public record ReconciliationMatch(
    String orderToken, MatchStatus status, List<DocumentId> invoiceIds, BigDecimal amountDiff) {

  public ReconciliationMatch {
    Objects.requireNonNull(status, "status");
    invoiceIds = List.copyOf(invoiceIds);
  }
}
