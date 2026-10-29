package dev.invoiceintel.extract;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code invoice-intel.llm.*} (architecture.md §10). Lives in {@code extract}, the module that uses
 * it, because {@code app} may not depend on {@code extract} (§4.1); start-up binds it once {@code
 * extract} is on the app's classpath (M3.8). No models is valid until then.
 *
 * @param maxConcurrentCalls concurrent calls by provider name (architecture.md §5.2)
 * @param defaultMaxConcurrentCalls cap of a provider not in {@code maxConcurrentCalls}
 */
@Validated
@ConfigurationProperties("invoice-intel.llm")
public record LlmProperties(
    List<@Valid ModelSpec> models,
    Map<String, @NotNull @Positive Integer> maxConcurrentCalls,
    @Positive Integer defaultMaxConcurrentCalls) {

  static final int DEFAULT_MAX_CONCURRENT_CALLS = 4;

  public LlmProperties {
    models = models == null ? List.of() : List.copyOf(models);
    maxConcurrentCalls = maxConcurrentCalls == null ? Map.of() : Map.copyOf(maxConcurrentCalls);
    if (defaultMaxConcurrentCalls == null) {
      defaultMaxConcurrentCalls = DEFAULT_MAX_CONCURRENT_CALLS;
    }
  }

  /** The per-provider concurrency caps. */
  public ProviderRateLimiter rateLimiter() {
    return new ProviderRateLimiter(maxConcurrentCalls, defaultMaxConcurrentCalls);
  }

  /**
   * @throws IllegalArgumentException if two models share a name
   */
  public ModelRegistry registry() {
    return new ModelRegistry(models);
  }
}
