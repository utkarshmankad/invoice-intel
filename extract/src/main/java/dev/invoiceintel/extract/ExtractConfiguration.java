package dev.invoiceintel.extract;

import jakarta.validation.Validator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Extract wiring (architecture.md §6.5, §10). It lives here because {@code app} may not depend on
 * {@code extract} (§4.1); the app's component scan finds it. Every bean is built at start-up, so an
 * unknown model or provider, or a missing API key for the chosen model, stops the app (fail fast).
 * Nothing is built while {@code invoice-intel.llm.extraction.model} is blank.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression(ExtractConfiguration.ENABLED)
public class ExtractConfiguration {

  /** True when an extraction model is configured; the Extract stage wiring uses it too. */
  public static final String ENABLED = "!'${invoice-intel.llm.extraction.model:}'.isBlank()";

  @Bean
  ModelRegistry modelRegistry(LlmProperties properties) {
    return properties.registry();
  }

  @Bean
  SpringAiLlmGateway llmGateway(
      LlmProperties properties, ModelRegistry registry, Validator validator, JdbcClient jdbc) {
    return new SpringAiLlmGateway(
        registry,
        LlmClients.create(properties),
        properties.rateLimiter(),
        validator,
        new JdbcLlmCallLog(jdbc));
  }

  @Bean
  InvoiceExtractor invoiceExtractor(
      SpringAiLlmGateway gateway, ModelRegistry registry, LlmProperties properties) {
    return new InvoiceExtractor(
        gateway, registry, gateway.providers(), new PromptLoader(), properties.extraction());
  }

  @Bean
  ExtractionStore extractionStore(JdbcClient jdbc) {
    return new ExtractionStore(jdbc);
  }
}
