package dev.invoiceintel.reconcile;

import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs and reads the reconciliation (REC-2..REC-4, architecture.md §6.7, ADR-26). A run reads the
 * current invoices and the current order-history rows ({@link OrderHistoryStore#findCurrent()}),
 * matches them and replaces the stored rows, all in one transaction, so the report always shows one
 * whole run.
 */
public final class ReconciliationService {

  private final InvoiceSource invoices;
  private final OrderHistoryStore orderHistory;
  private final ReconciliationStore store;
  private final ReconciliationMatcher matcher;
  private final TransactionTemplate write;
  private final TransactionTemplate read;

  public ReconciliationService(
      JdbcClient jdbc,
      PlatformTransactionManager transactionManager,
      ReconciliationTolerance tolerance,
      InvoiceSource invoices) {
    this.invoices = Objects.requireNonNull(invoices, "invoices");
    this.orderHistory = new OrderHistoryStore(jdbc);
    this.store = new ReconciliationStore(jdbc);
    this.matcher = new ReconciliationMatcher(tolerance);
    Objects.requireNonNull(transactionManager, "transactionManager");
    this.write = new TransactionTemplate(transactionManager);
    var readOnly = new TransactionTemplate(transactionManager);
    readOnly.setReadOnly(true);
    this.read = readOnly;
    this.write.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
  }

  /** Reconciles every current invoice with the order history and stores the result. */
  public ReconciliationReport run() {
    return Objects.requireNonNull(
        write.execute(
            _ -> {
              var matches = matcher.match(invoices.currentInvoices(), orderHistory.findCurrent());
              store.replaceAll(matches);
              return ReconciliationReport.of(matches);
            }));
  }

  /** The report of the latest run; empty counts before the first run. */
  public ReconciliationReport report() {
    return Objects.requireNonNull(read.execute(_ -> ReconciliationReport.of(store.findAll())));
  }
}
