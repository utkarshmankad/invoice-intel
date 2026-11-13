package dev.invoiceintel.app.config;

import dev.invoiceintel.pii.RedactedPageReader;
import dev.invoiceintel.pipeline.DocumentDetailsReader;
import dev.invoiceintel.pipeline.ReviewQueueReader;
import dev.invoiceintel.storage.BlobStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Read side of the document API (UC2, architecture.md §8), the review queue (REV-1, §6.8) and the
 * document page's masked page images (REV-2, ADR-29).
 */
@Configuration(proxyBeanMethods = false)
public class DocumentApiConfiguration {

  @Bean
  DocumentDetailsReader documentDetailsReader(
      JdbcClient jdbc, PlatformTransactionManager transactionManager) {
    return new DocumentDetailsReader(jdbc, transactionManager);
  }

  @Bean
  ReviewQueueReader reviewQueueReader(JdbcClient jdbc) {
    return new ReviewQueueReader(jdbc);
  }

  @Bean
  RedactedPageReader redactedPageReader(BlobStore blobStore, JdbcClient jdbc) {
    return new RedactedPageReader(blobStore, jdbc);
  }
}
