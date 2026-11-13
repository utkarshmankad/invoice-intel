package dev.invoiceintel.app.reconcile;

import dev.invoiceintel.pii.PiiType;
import dev.invoiceintel.pii.TokenGenerator;
import dev.invoiceintel.reconcile.OrderHistoryImporter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Order-history import (REC-1, architecture.md §6.7). Order numbers get the same token as the PII
 * stage gives them in invoices ({@link PiiType#ORDER_NUMBER}), so the matcher can join them.
 */
@Configuration(proxyBeanMethods = false)
public class OrderHistoryConfiguration {

  @Bean
  OrderHistoryImporter orderHistoryImporter(
      JdbcClient jdbc, PlatformTransactionManager transactionManager, TokenGenerator tokens) {
    return new OrderHistoryImporter(
        jdbc, transactionManager, orderNumber -> tokens.token(PiiType.ORDER_NUMBER, orderNumber));
  }
}
