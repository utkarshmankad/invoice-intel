package dev.invoiceintel.app.config;

import dev.invoiceintel.pipeline.DocumentDetailsReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/** Read side of the document API (UC2, architecture.md §8). */
@Configuration(proxyBeanMethods = false)
public class DocumentApiConfiguration {

  @Bean
  DocumentDetailsReader documentDetailsReader(
      JdbcClient jdbc, PlatformTransactionManager transactionManager) {
    return new DocumentDetailsReader(jdbc, transactionManager);
  }
}
