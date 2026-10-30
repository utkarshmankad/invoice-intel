package dev.invoiceintel.extract;

import dev.invoiceintel.pii.RedactedContent;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Extracts the invoice schema from one document's redacted content with the configured model and
 * strategy (EXT-1, EXT-2, architecture.md §6.5): renders the strategy's prompt, calls the gateway
 * and maps the cited line markers of a valid result to word boxes (§6.5 Evidence).
 */
public final class InvoiceExtractor {

  private final LlmGateway gateway;
  private final ModelSpec model;
  private final ExtractionRequest<InvoiceExtraction> request;

  /**
   * @throws IllegalArgumentException if the model isn't in the registry, doesn't support the
   *     strategy, or its provider has no client (e.g. no Anthropic API key), so a bad setup stops
   *     start-up instead of failing every document
   * @param providers the providers {@code gateway} has a client for
   */
  public InvoiceExtractor(
      LlmGateway gateway,
      ModelRegistry registry,
      Set<String> providers,
      PromptLoader prompts,
      LlmProperties.Extraction settings) {
    this.gateway = Objects.requireNonNull(gateway, "gateway");
    this.model =
        registry
            .find(settings.model())
            .orElseThrow(
                () ->
                    new IllegalArgumentException("unknown extraction model: " + settings.model()));
    if (!settings.strategy().supportedBy(model)) {
      throw new IllegalArgumentException(
          "model " + model.name() + " does not support strategy " + settings.strategy());
    }
    if (!providers.contains(model.provider())) {
      throw new IllegalArgumentException(
          "no client for provider " + model.provider() + " of model " + model.name());
    }
    // The prompts take no variables: everything document-specific is in the user message.
    this.request =
        prompts.request(model.name(), settings.strategy(), Map.of(), InvoiceExtraction.class);
  }

  /** The configured model. */
  public ModelSpec model() {
    return model;
  }

  /** The request every document is sent with (model, prompt, strategy), for the extraction row. */
  public ExtractionRequest<InvoiceExtraction> request() {
    return request;
  }

  /**
   * The model's answer for {@code content}. A {@link LlmResult.Succeeded} value has its evidence
   * mapped; {@link LlmResult#responses()} are the raw answers for the leak check (PII-6).
   */
  public LlmResult<InvoiceExtraction> extract(RedactedContent content) {
    LlmResult<InvoiceExtraction> result = gateway.extract(content, request);
    if (result instanceof LlmResult.Succeeded<InvoiceExtraction> succeeded) {
      return new LlmResult.Succeeded<>(
          EvidenceMapper.map(succeeded.value(), content), succeeded.calls(), succeeded.responses());
    }
    return result;
  }
}
