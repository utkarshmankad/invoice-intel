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
import static org.assertj.core.api.Assertions.tuple;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.pii.RedactedContent;
import dev.invoiceintel.pii.RedactedContentFixtures;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import tools.jackson.databind.json.JsonMapper;

/**
 * The gateway against a real Spring AI client (Ollama) talking to WireMock: success, repair retry
 * and failure (M3.3). Stub responses are written by hand; no real LLM is called (testing.md).
 */
class SpringAiLlmGatewayTest {

  /** A small output record, so stubs stay readable; the gateway is generic in its type. */
  record Totals(@NotBlank String invoiceNumber, @NotNull BigDecimal grandTotal) {}

  private static final PromptRef PROMPT = new PromptRef("totals", 1);
  private static final String SCENARIO = "repair";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static WireMockServer wireMock;
  private static ValidatorFactory validatorFactory;

  private final List<LlmCall> recorded = new ArrayList<>();
  private final DocumentId documentId = DocumentId.random();
  private final RedactedContent content =
      RedactedContentFixtures.of(documentId, List.of("Invoice INV-TOKEN-1\nGrand Total 1180.00"));
  private SpringAiLlmGateway gateway;

  @BeforeAll
  static void start() {
    wireMock = new WireMockServer(wireMockConfig().dynamicPort());
    wireMock.start();
    validatorFactory = Validation.buildDefaultValidatorFactory();
  }

  @AfterAll
  static void stop() {
    wireMock.stop();
    validatorFactory.close();
  }

  @BeforeEach
  void setUp() {
    wireMock.resetAll();
    ChatModel ollama =
        OllamaChatModel.builder()
            .ollamaApi(OllamaApi.builder().baseUrl(wireMock.baseUrl()).build())
            .build();
    gateway = gateway(Map.of("ollama", ollama));
  }

  private SpringAiLlmGateway gateway(Map<String, ChatModel> clients) {
    var registry =
        new ModelRegistry(
            List.of(
                new ModelSpec(
                    "local-text",
                    "ollama",
                    "example-local-8b",
                    Set.of(ModelCapability.TEXT),
                    new BigDecimal("2.00"),
                    new BigDecimal("8.00"))));
    return new SpringAiLlmGateway(
        registry,
        clients,
        new ProviderRateLimiter(Map.of(), 2),
        validatorFactory.getValidator(),
        recorded::add);
  }

  /** The instructions come from the versioned test prompt {@code prompts/totals/v1.st}. */
  private static ExtractionRequest<Totals> request() {
    return new PromptLoader()
        .request("local-text", PROMPT, Map.of("currency", "INR"), Totals.class);
  }

  /** An Ollama /api/chat response with {@code content} as the assistant message. */
  private static String chatResponse(String content, int promptTokens, int outputTokens) {
    return JSON.writeValueAsString(
        Map.of(
            "model",
            "example-local-8b",
            "created_at",
            "2026-10-30T00:00:00Z",
            "message",
            Map.of("role", "assistant", "content", content),
            "done",
            true,
            "done_reason",
            "stop",
            "prompt_eval_count",
            promptTokens,
            "eval_count",
            outputTokens));
  }

  private static void stubChat(String scenarioState, String nextState, String body) {
    var stub =
        post(urlEqualTo("/api/chat"))
            .inScenario(SCENARIO)
            .whenScenarioStateIs(scenarioState)
            .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body));
    if (nextState != null) {
      stub = stub.willSetStateTo(nextState);
    }
    wireMock.stubFor(stub);
  }

  @Test
  void extract_validResponse_returnsValueAndRecordsOneCall() {
    stubChat(
        Scenario.STARTED,
        null,
        chatResponse("{\"invoiceNumber\":\"INV-TOKEN-1\",\"grandTotal\":1180.00}", 1000, 200));

    LlmResult<Totals> result = gateway.extract(content, request());

    assertThat(result).isInstanceOf(LlmResult.Succeeded.class);
    Totals totals = ((LlmResult.Succeeded<Totals>) result).value();
    assertThat(totals.invoiceNumber()).isEqualTo("INV-TOKEN-1");
    assertThat(totals.grandTotal()).isEqualByComparingTo("1180.00");
    assertThat(recorded).isEqualTo(result.calls()).hasSize(1);
    LlmCall call = recorded.getFirst();
    assertThat(call)
        .extracting(
            LlmCall::documentId,
            LlmCall::stage,
            LlmCall::provider,
            LlmCall::modelId,
            LlmCall::promptVersion,
            LlmCall::attempt,
            LlmCall::inputTokens,
            LlmCall::outputTokens,
            LlmCall::outcome)
        .containsExactly(
            documentId,
            "EXTRACT",
            "ollama",
            "example-local-8b",
            "totals/v1",
            1,
            1000,
            200,
            LlmOutcome.SUCCEEDED);
    // 1000 × 2.00 / 1M + 200 × 8.00 / 1M
    assertThat(call.costUsd()).isEqualByComparingTo("0.0036");
    assertThat(call.latencyMs()).isNotNegative();
    wireMock.verify(
        postRequestedFor(urlEqualTo("/api/chat"))
            .withRequestBody(containing("example-local-8b"))
            .withRequestBody(containing("Extract the totals for INR."))
            // TEXT strategy: each line of the redacted text carries its marker (EXT-2).
            .withRequestBody(
                containing("[p1:l1] Invoice INV-TOKEN-1\\n[p1:l2] Grand Total 1180.00"))
            .withRequestBody(containing("grandTotal")));
  }

  @Test
  void extract_invalidThenValid_repairsOnce() {
    stubChat(Scenario.STARTED, "repaired", chatResponse("{\"invoiceNumber\":\"\"}", 900, 20));
    stubChat(
        "repaired",
        null,
        chatResponse("{\"invoiceNumber\":\"INV-TOKEN-1\",\"grandTotal\":1180}", 1100, 30));

    LlmResult<Totals> result = gateway.extract(content, request());

    assertThat(result).isInstanceOf(LlmResult.Succeeded.class);
    assertThat(recorded)
        .extracting(LlmCall::attempt, LlmCall::outcome)
        .containsExactly(tuple(1, LlmOutcome.INVALID_OUTPUT), tuple(2, LlmOutcome.SUCCEEDED));
    // The repair request carries the validation errors back to the model.
    wireMock.verify(
        postRequestedFor(urlEqualTo("/api/chat"))
            .withRequestBody(containing("previous response was invalid"))
            .withRequestBody(containing("grandTotal must not be null")));
  }

  @Test
  void extract_invalidTwice_failsWithInvalidOutput() {
    stubChat(Scenario.STARTED, "repaired", chatResponse("not json", 900, 5));
    stubChat("repaired", null, chatResponse("{\"invoiceNumber\":", 1000, 5));

    LlmResult<Totals> result = gateway.extract(content, request());

    assertThat(result)
        .isInstanceOfSatisfying(
            LlmResult.Failed.class,
            failed -> assertThat(failed.code()).isEqualTo(LlmResult.Failed.INVALID_OUTPUT));
    assertThat(recorded)
        .extracting(LlmCall::attempt, LlmCall::outcome)
        .containsExactly(tuple(1, LlmOutcome.INVALID_OUTPUT), tuple(2, LlmOutcome.INVALID_OUTPUT));
    wireMock.verify(2, postRequestedFor(urlEqualTo("/api/chat")));
  }

  @Test
  void extract_providerError_failsWithoutRetryAndRecordsError() {
    ChatModel failing =
        prompt -> {
          throw new IllegalStateException("HTTP 503");
        };
    gateway = gateway(Map.of("ollama", failing));

    LlmResult<Totals> result = gateway.extract(content, request());

    assertThat(result)
        .isInstanceOfSatisfying(
            LlmResult.Failed.class,
            failed -> assertThat(failed.code()).isEqualTo(LlmResult.Failed.ERROR));
    assertThat(recorded)
        .singleElement()
        .satisfies(
            call -> {
              assertThat(call.outcome()).isEqualTo(LlmOutcome.ERROR);
              assertThat(call.inputTokens()).isZero();
              assertThat(call.costUsd()).isEqualByComparingTo("0");
            });
  }

  @Test
  void extract_unknownModel_isRejected() {
    var unknown = new ExtractionRequest<>("nope", PROMPT, "x", Totals.class);

    assertThatThrownBy(() -> gateway.extract(content, unknown))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown model");
    assertThat(recorded).isEmpty();
  }

  @Test
  void extract_providerWithoutClient_isRejected() {
    gateway = gateway(Map.of());

    assertThatThrownBy(() -> gateway.extract(content, request()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no client for provider");
  }

  @Test
  void requestAndResult_toString_printNoText() {
    var succeeded = new LlmResult.Succeeded<>(new Totals("INV-TOKEN-1", BigDecimal.ONE), List.of());

    assertThat(request().toString()).doesNotContain("Extract the invoice").contains("totals/v1");
    assertThat(succeeded.toString()).doesNotContain("INV-TOKEN-1");
  }

  @Test
  void requestBody_hasModelAndSchema() {
    stubChat(
        Scenario.STARTED,
        null,
        chatResponse("{\"invoiceNumber\":\"INV-TOKEN-1\",\"grandTotal\":1}", 1, 1));

    gateway.extract(content, request());

    wireMock.verify(
        postRequestedFor(urlEqualTo("/api/chat"))
            .withRequestBody(
                com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath(
                    "$.model", equalTo("example-local-8b"))));
  }
}
