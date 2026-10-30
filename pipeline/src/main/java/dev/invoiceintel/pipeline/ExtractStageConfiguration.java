package dev.invoiceintel.pipeline;

import dev.invoiceintel.extract.ExtractConfiguration;
import dev.invoiceintel.extract.ExtractionStore;
import dev.invoiceintel.extract.InvoiceExtractor;
import dev.invoiceintel.pii.LeakChecker;
import dev.invoiceintel.pii.PiiSettings;
import dev.invoiceintel.pii.RedactedContentLoader;
import dev.invoiceintel.storage.BlobStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Extract stage wiring (architecture.md §5.1, §6.5). It lives in {@code pipeline} because {@code
 * app} may not use {@code extract} types (§4.1); the app's component scan finds it. Like {@link
 * ExtractConfiguration} it is on only when {@code invoice-intel.llm.extraction.model} is set;
 * without it the stage has no handler and documents stop in {@code PII_CLEAN}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression(ExtractConfiguration.ENABLED)
public class ExtractStageConfiguration {

  @Bean
  ExtractStageHandler extractStage(
      JdbcClient jdbc,
      BlobStore blobStore,
      PiiSettings piiSettings,
      InvoiceExtractor extractor,
      LeakChecker leakChecker,
      ExtractionStore store) {
    return new ExtractStageHandler(
        jdbc,
        new RedactedContentLoader(blobStore, jdbc, piiSettings.mode()),
        extractor,
        leakChecker,
        store);
  }
}
