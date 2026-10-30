package dev.invoiceintel.extract;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.common.extraction.InvoiceExtraction;
import dev.invoiceintel.pii.RedactedContent;
import dev.invoiceintel.pii.RedactedContentFixtures;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The extractor with real provider clients from {@link LlmClients} against WireMock: the Anthropic
 * Messages API and Ollama. Responses are hand-written from the schema (testing.md).
 */
class InvoiceExtractorTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static WireMockServer wireMock;
  private static ValidatorFactory validatorFactory;
  private static String extractionJson;

  private final List<LlmCall> recorded = new ArrayList<>();
  private final RedactedContent content =
      RedactedContentFixtures.of(
          DocumentId.random(), List.of("Invoice No INVNO_TOKEN_01\nGrand Total 118.00"));

  @BeforeAll
  static void start() throws IOException {
    wireMock = new WireMockServer(wireMockConfig().dynamicPort());
    wireMock.start();
    validatorFactory = Validation.buildDefaultValidatorFactory();
    try (InputStream in =
        InvoiceExtractorTest.class.getResourceAsStream(
            "/fixtures/llm/invoice-extraction-response.json")) {
      if (in == null) {
        throw new UncheckedIOException(new IOException("fixture missing"));
      }
      extractionJson = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @AfterAll
  static void stop() {
    wireMock.stop();
    validatorFactory.close();
  }

  @BeforeEach
  void reset() {
    wireMock.resetAll();
  }

  private LlmProperties properties(String model, ExtractionStrategy strategy) {
    return new LlmProperties(
        List.of(
            new ModelSpec(
                "cloud",
                LlmClients.ANTHROPIC,
                "example-cloud-1",
                Set.of(ModelCapability.TEXT, ModelCapability.VISION),
                new BigDecimal("2.00"),
                new BigDecimal("10.00")),
            new ModelSpec(
                "local",
                LlmClients.OLLAMA,
                "example-local-8b",
                Set.of(ModelCapability.TEXT),
                BigDecimal.ZERO,
                BigDecimal.ZERO)),
        null,
        null,
        new LlmProperties.Extraction(model, strategy),
        new LlmProperties.Ollama(wireMock.baseUrl()),
        new LlmProperties.Anthropic(wireMock.baseUrl(), "test-key-not-real", 1024, null));
  }

  private InvoiceExtractor extractor(LlmProperties properties) {
    var gateway =
        new SpringAiLlmGateway(
            properties.registry(),
            LlmClients.create(properties),
            properties.rateLimiter(),
            validatorFactory.getValidator(),
            recorded::add);
    return new InvoiceExtractor(
        gateway,
        properties.registry(),
        gateway.providers(),
        new PromptLoader(),
        properties.extraction());
  }

  /** A Messages API response with {@code text} as the assistant's text block. */
  private static String anthropicResponse(String text) {
    return JSON.writeValueAsString(
        Map.of(
            "id", "msg_test_01",
            "type", "message",
            "role", "assistant",
            "model", "example-cloud-1",
            "content", List.of(Map.of("type", "text", "text", text)),
            "stop_reason", "end_turn",
            "usage", Map.of("input_tokens", 1200, "output_tokens", 300)));
  }

  @Test
  void extract_anthropic_returnsMappedExtractionAndRecordsCost() {
    wireMock.stubFor(
        post(urlEqualTo("/v1/messages"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(anthropicResponse(extractionJson))));

    LlmResult<InvoiceExtraction> result =
        extractor(properties("cloud", ExtractionStrategy.TEXT)).extract(content);

    assertThat(result)
        .isInstanceOfSatisfying(
            LlmResult.Succeeded.class,
            succeeded -> {
              var extraction = (InvoiceExtraction) succeeded.value();
              assertThat(extraction.grandTotal().value()).isEqualByComparingTo("118.00");
              // Evidence mapping ran: the cited line p1:l1 got its word box.
              assertThat(extraction.invoiceNumber().evidence().box()).isNotNull();
            });
    assertThat(result.responses()).containsExactly(extractionJson);
    assertThat(recorded)
        .singleElement()
        .satisfies(
            call -> {
              assertThat(call.provider()).isEqualTo("anthropic");
              assertThat(call.modelId()).isEqualTo("example-cloud-1");
              assertThat(call.promptVersion()).isEqualTo("invoice-extraction/v1");
              // 1200 × 2.00 + 300 × 10.00 per million.
              assertThat(call.costUsd()).isEqualByComparingTo("0.0054");
            });
    wireMock.verify(
        postRequestedFor(urlEqualTo("/v1/messages"))
            .withHeader("x-api-key", equalTo("test-key-not-real"))
            .withRequestBody(containing("\"model\":\"example-cloud-1\""))
            .withRequestBody(containing("[p1:l1]")));
  }

  @Test
  void extract_ollamaInvalidOutput_failsAndKeepsBothResponses() {
    wireMock.stubFor(
        post(urlEqualTo("/api/chat"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        JSON.writeValueAsString(
                            Map.of(
                                "model",
                                "example-local-8b",
                                "message",
                                Map.of("role", "assistant", "content", "{}"),
                                "done",
                                true)))));

    LlmResult<InvoiceExtraction> result =
        extractor(properties("local", ExtractionStrategy.TEXT)).extract(content);

    assertThat(result)
        .isInstanceOfSatisfying(
            LlmResult.Failed.class,
            failed -> assertThat(failed.code()).isEqualTo(LlmResult.Failed.INVALID_OUTPUT));
    assertThat(result.responses()).containsExactly("{}", "{}");
  }

  @Test
  void request_usesTheStrategysPrompt() {
    var extractor = extractor(properties("cloud", ExtractionStrategy.HYBRID));

    assertThat(extractor.request().strategy()).isEqualTo(ExtractionStrategy.HYBRID);
    assertThat(extractor.request().prompt().id()).isEqualTo("invoice-extraction-hybrid/v1");
    assertThat(extractor.model().modelId()).isEqualTo("example-cloud-1");
  }

  @Test
  void new_unknownModel_fails() {
    assertThatThrownBy(() -> extractor(properties("missing", ExtractionStrategy.TEXT)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown extraction model");
  }

  @Test
  void new_strategyTheModelLacks_fails() {
    assertThatThrownBy(() -> extractor(properties("local", ExtractionStrategy.VISION)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not support strategy");
  }

  @Test
  void new_providerWithoutClient_fails() {
    LlmProperties base = properties("cloud", ExtractionStrategy.TEXT);
    var noKey =
        new LlmProperties(
            base.models(),
            null,
            null,
            base.extraction(),
            base.ollama(),
            new LlmProperties.Anthropic(wireMock.baseUrl(), "", null, null));

    assertThatThrownBy(() -> extractor(noKey))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no client for provider anthropic");
  }
}
