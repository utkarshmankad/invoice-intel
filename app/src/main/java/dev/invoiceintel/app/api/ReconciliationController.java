package dev.invoiceintel.app.api;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.reconcile.MatchStatus;
import dev.invoiceintel.reconcile.ReconciliationMatch;
import dev.invoiceintel.reconcile.ReconciliationReport;
import dev.invoiceintel.reconcile.ReconciliationService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reconciliation run and report (REC-2, REC-3, architecture.md §6.7, §8). Responses carry order
 * tokens, document IDs, counts and amounts only; never order numbers (PII-3).
 */
@RestController
@RequestMapping("/api/reconciliation")
@SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification = "Inventory marker, not a defect; /api/** requires admin auth (ADR-12)")
public class ReconciliationController {

  private final ReconciliationService reconciliation;

  public ReconciliationController(ReconciliationService reconciliation) {
    this.reconciliation = reconciliation;
  }

  /** Reconciles every decided document with the current order history and returns the report. */
  @PostMapping("/run")
  public ReportView run() {
    return ReportView.of(reconciliation.run());
  }

  /** The report of the latest run; zero counts and no rows before the first run. */
  @GetMapping
  public ReportView report() {
    return ReportView.of(reconciliation.report());
  }

  /**
   * The report (REC-3).
   *
   * @param orders rows per status
   * @param documents documents in all rows
   * @param matchedDocuments documents in {@code MATCHED} rows
   * @param matchRate {@code matchedDocuments / documents}, null when there are no documents
   */
  public record ReportView(
      Map<MatchStatus, Integer> orders,
      int documents,
      int matchedDocuments,
      Double matchRate,
      List<RowView> rows) {

    public ReportView {
      orders = new EnumMap<>(orders);
      rows = List.copyOf(rows);
    }

    static ReportView of(ReconciliationReport report) {
      var rate = report.matchRate();
      return new ReportView(
          report.orders(),
          report.documents(),
          report.matchedDocuments(),
          rate.isPresent() ? rate.getAsDouble() : null,
          report.rows().stream().map(RowView::of).toList());
    }
  }

  /**
   * One order's row.
   *
   * @param orderToken the order number's token; null for an invoice without an order number
   * @param amountDiff invoices minus credit notes minus the order total; null when not computable
   */
  public record RowView(
      String orderToken, MatchStatus status, List<UUID> invoiceIds, BigDecimal amountDiff) {

    public RowView {
      invoiceIds = List.copyOf(invoiceIds);
    }

    static RowView of(ReconciliationMatch match) {
      return new RowView(
          match.orderToken(),
          match.status(),
          match.invoiceIds().stream().map(DocumentId::value).toList(),
          match.amountDiff());
    }
  }
}
