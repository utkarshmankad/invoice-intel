package dev.invoiceintel.extract;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code invoice-intel.llm.*} (architecture.md §10). Lives in {@code extract}, the module that uses
 * it, because {@code app} may not depend on {@code extract} (§4.1); start-up binds it through the
 * app's properties scan. The Extract stage runs only when {@code extraction.model} names a model.
 *
 * @param maxConcurrentCalls concurrent calls by provider name (architecture.md §5.2)
 * @param defaultMaxConcurrentCalls cap of a provider not in {@code maxConcurrentCalls}
 * @param extraction the model and strategy of the Extract stage
 * @param ollama the local provider ({@value LlmClients#OLLAMA})
 * @param anthropic the cloud provider ({@value LlmClients#ANTHROPIC}, ADR-18)
 */
@Validated
@ConfigurationProperties("invoice-intel.llm")
public record LlmProperties(
    List<@Valid ModelSpec> models,
    Map<String, @NotNull @Positive Integer> maxConcurrentCalls,
    @Positive Integer defaultMaxConcurrentCalls,
    @Valid Extraction extraction,
    @Valid Ollama ollama,
    @Valid Anthropic anthropic) {

  static final int DEFAULT_MAX_CONCURRENT_CALLS = 4;

  public LlmProperties {
    models = models == null ? List.of() : List.copyOf(models);
    maxConcurrentCalls = maxConcurrentCalls == null ? Map.of() : Map.copyOf(maxConcurrentCalls);
    if (defaultMaxConcurrentCalls == null) {
      defaultMaxConcurrentCalls = DEFAULT_MAX_CONCURRENT_CALLS;
    }
    extraction = extraction == null ? new Extraction(null, null) : extraction;
    ollama = ollama == null ? new Ollama(null) : ollama;
    anthropic = anthropic == null ? new Anthropic(null, null, null, null) : anthropic;
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

  /**
   * @param model registry name of the model the Extract stage uses; blank turns the stage off, so
   *     documents stop in {@code PII_CLEAN}
   * @param strategy what the model sees (EXT-2), {@code TEXT} by default
   */
  public record Extraction(String model, ExtractionStrategy strategy) {

    public Extraction {
      model = model == null ? "" : model.strip();
      strategy = strategy == null ? ExtractionStrategy.TEXT : strategy;
    }

    public boolean enabled() {
      return !model.isEmpty();
    }
  }

  /**
   * @param baseUrl the Ollama server, loopback by default
   */
  public record Ollama(String baseUrl) {

    static final String DEFAULT_BASE_URL = "http://127.0.0.1:11434";

    public Ollama {
      baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl;
    }
  }

  /**
   * @param baseUrl the Messages API host
   * @param apiKey from {@code LLM_ANTHROPIC_API_KEY} only, never from a config file (no secrets in
   *     config); blank means no Anthropic client
   * @param maxTokens output token limit per call; the Messages API requires one
   * @param timeout per call, the SDK's own retries included
   */
  public record Anthropic(
      String baseUrl, String apiKey, @Positive Integer maxTokens, Duration timeout) {

    static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    static final int DEFAULT_MAX_TOKENS = 8192;
    static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);

    public Anthropic {
      baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl;
      apiKey = apiKey == null ? "" : apiKey.strip();
      if (maxTokens == null) {
        maxTokens = DEFAULT_MAX_TOKENS;
      }
      timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
    }

    /** Never prints the key. */
    @Override
    public String toString() {
      return "Anthropic[baseUrl="
          + baseUrl
          + ", apiKey="
          + (apiKey.isEmpty() ? "unset" : "set")
          + ", maxTokens="
          + maxTokens
          + ", timeout="
          + timeout
          + "]";
    }
  }
}
