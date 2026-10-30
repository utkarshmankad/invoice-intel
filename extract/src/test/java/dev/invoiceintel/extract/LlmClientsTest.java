package dev.invoiceintel.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.ollama.OllamaChatModel;

class LlmClientsTest {

  private static ModelSpec model(String name, String provider) {
    return new ModelSpec(
        name,
        provider,
        "example-" + name,
        Set.of(ModelCapability.TEXT),
        BigDecimal.ONE,
        BigDecimal.ONE);
  }

  private static LlmProperties properties(List<ModelSpec> models, String anthropicKey) {
    return new LlmProperties(
        models,
        null,
        null,
        null,
        new LlmProperties.Ollama("http://127.0.0.1:1"),
        new LlmProperties.Anthropic("http://127.0.0.1:1", anthropicKey, null, null));
  }

  @Test
  void create_bothProvidersWithKey_buildsOneClientEach() {
    var clients =
        LlmClients.create(
            properties(
                List.of(
                    model("cloud", "anthropic"),
                    model("cloud-2", "anthropic"),
                    model("local", "ollama")),
                "test-key-not-real"));

    assertThat(clients).containsOnlyKeys("anthropic", "ollama");
    assertThat(clients.get("anthropic")).isInstanceOf(AnthropicChatModel.class);
    assertThat(clients.get("ollama")).isInstanceOf(OllamaChatModel.class);
  }

  @Test
  void create_anthropicWithoutKey_hasNoAnthropicClient() {
    var clients =
        LlmClients.create(
            properties(List.of(model("cloud", "anthropic"), model("local", "ollama")), " "));

    assertThat(clients).containsOnlyKeys("ollama");
  }

  @Test
  void create_unknownProvider_fails() {
    assertThatThrownBy(() -> LlmClients.create(properties(List.of(model("x", "openai")), "")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("openai");
  }

  @Test
  void create_noModels_hasNoClients() {
    assertThat(LlmClients.create(properties(List.of(), ""))).isEqualTo(Map.of());
  }

  @Test
  void anthropicDefaults_andToString_hideTheKey() {
    var anthropic = new LlmProperties.Anthropic(null, "secret-value", null, null);

    assertThat(anthropic.baseUrl()).isEqualTo("https://api.anthropic.com");
    assertThat(anthropic.maxTokens()).isEqualTo(8192);
    assertThat(anthropic.timeout()).isEqualTo(Duration.ofMinutes(2));
    assertThat(anthropic.toString()).doesNotContain("secret-value").contains("apiKey=set");
  }

  @Test
  void defaults_extractionOffAndLoopbackOllama() {
    var properties = new LlmProperties(null, null, null, null, null, null);

    assertThat(properties.extraction().enabled()).isFalse();
    assertThat(properties.extraction().strategy()).isEqualTo(ExtractionStrategy.TEXT);
    assertThat(properties.ollama().baseUrl()).isEqualTo("http://127.0.0.1:11434");
    assertThat(properties.anthropic().apiKey()).isEmpty();
  }
}
