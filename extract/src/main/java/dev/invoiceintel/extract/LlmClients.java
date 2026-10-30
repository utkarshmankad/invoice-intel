package dev.invoiceintel.extract;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.AnthropicSetup;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;

/**
 * Builds the chat client of each provider the model registry uses (EXT-3, NFR-8, ADR-18). Only
 * {@code extract} creates Spring AI clients (architecture.md §4.1); the gateway sets each call's
 * model ID, so one client per provider serves all its models.
 */
public final class LlmClients {

  /** Local models through an Ollama server. */
  public static final String OLLAMA = "ollama";

  /** Claude models through the Anthropic Messages API. */
  public static final String ANTHROPIC = "anthropic";

  private LlmClients() {}

  /**
   * One client per provider named by a model in {@code properties}. Anthropic gets a client only
   * when its API key is set, so a local-only setup needs no key.
   *
   * @throws IllegalArgumentException if a model names a provider other than {@value #OLLAMA} and
   *     {@value #ANTHROPIC}
   */
  public static Map<String, ChatModel> create(LlmProperties properties) {
    Objects.requireNonNull(properties, "properties");
    Map<String, ChatModel> clients = new HashMap<>();
    for (ModelSpec model : properties.models()) {
      String provider = model.provider();
      if (clients.containsKey(provider)) {
        continue;
      }
      switch (provider) {
        case OLLAMA -> clients.put(provider, ollama(properties.ollama()));
        case ANTHROPIC -> {
          if (!properties.anthropic().apiKey().isEmpty()) {
            clients.put(provider, anthropic(properties.anthropic()));
          }
        }
        default -> throw new IllegalArgumentException("unknown LLM provider: " + provider);
      }
    }
    return Map.copyOf(clients);
  }

  static ChatModel ollama(LlmProperties.Ollama config) {
    return OllamaChatModel.builder()
        .ollamaApi(OllamaApi.builder().baseUrl(config.baseUrl()).build())
        .build();
  }

  static ChatModel anthropic(LlmProperties.Anthropic config) {
    // Explicit base URL and key, so the SDK never falls back to ANTHROPIC_* environment variables.
    // Retries stay at the SDK's default; the gateway adds only the repair retry (§6.5).
    var client =
        AnthropicSetup.setupSyncClient(
            config.baseUrl(), config.apiKey(), config.timeout(), null, null, Map.of());
    return AnthropicChatModel.builder()
        .anthropicClient(client)
        .options(AnthropicChatOptions.builder().maxTokens(config.maxTokens()).build())
        .build();
  }
}
