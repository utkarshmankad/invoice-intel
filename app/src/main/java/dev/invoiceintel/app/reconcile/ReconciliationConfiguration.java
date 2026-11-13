package dev.invoiceintel.app.reconcile;

import dev.invoiceintel.pipeline.ReconciliationInvoiceReader;
import dev.invoiceintel.reconcile.ReconciliationService;
import dev.invoiceintel.reconcile.ReconciliationTolerance;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Reconciliation runs and report (REC-2..REC-4, architecture.md §6.7, ADR-26): the matcher reads
 * the documents the pipeline decided, through {@code pipeline}'s reader, with the default
 * tolerances.
 */
@Configuration(proxyBeanMethods = false)
public class ReconciliationConfiguration {

  @Bean
  ReconciliationService reconciliationService(
      JdbcClient jdbc, PlatformTransactionManager transactionManager) {
    return new ReconciliationService(
        jdbc,
        transactionManager,
        ReconciliationTolerance.DEFAULT,
        new ReconciliationInvoiceReader(jdbc));
  }
}
