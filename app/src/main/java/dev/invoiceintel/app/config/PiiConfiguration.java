package dev.invoiceintel.app.config;

import dev.invoiceintel.pii.PiiDetectors;
import dev.invoiceintel.pii.PiiKeys;
import dev.invoiceintel.pii.PiiSettings;
import dev.invoiceintel.pii.PiiVault;
import dev.invoiceintel.pii.TokenGenerator;
import java.io.IOException;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * PII stage wiring (architecture.md §6.3, §10). Every bean is built at start-up, so a bad {@code
 * invoice-intel.pii.*} setup, a missing NER model or a missing keyset stops the app before it takes
 * any document (fail fast) instead of failing the first PII job.
 */
@Configuration(proxyBeanMethods = false)
public class PiiConfiguration {

  @Bean
  PiiSettings piiSettings(InvoiceIntelProperties properties) {
    return properties.pii().toSettings();
  }

  /** Loads the NER model when that layer is on (ADR-15); closing releases it. */
  @Bean(destroyMethod = "close")
  PiiDetectors piiDetectors(PiiSettings settings) throws IOException {
    return PiiDetectors.create(settings);
  }

  @Bean
  TokenGenerator tokenGenerator(ObjectProvider<PiiKeyEnvironment> keys) {
    return PiiKeys.tokenGenerator(variables(keys));
  }

  @Bean
  PiiVault piiVault(ObjectProvider<PiiKeyEnvironment> keys) {
    return PiiKeys.vault(variables(keys));
  }

  /** Keysets come from the environment only, never from config files (PII-7). */
  private static Map<String, String> variables(ObjectProvider<PiiKeyEnvironment> keys) {
    return keys.getIfAvailable(() -> new PiiKeyEnvironment(System.getenv())).variables();
  }
}
