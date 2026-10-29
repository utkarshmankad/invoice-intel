package dev.invoiceintel.extract;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.notContaining;
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
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
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
 * and failure (M3.3), and the TEXT, VISION and HYBRID inputs (M3.5). Stub responses are written by
 * hand; no real LLM is called (testing.md).
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
                    new BigDecimal("8.00")),
                new ModelSpec(
                    "local-vision",
                    "ollama",
                    "example-local-vl-7b",
                    Set.of(ModelCapability.TEXT, ModelCapability.VISION),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO)));
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
            .withRequestBody(matchingJsonPath("$.model", equalTo("example-local-8b"))));
  }

  /** A synthetic 2×2 PNG standing in for a masked page image (no document content). */
  private static byte[] png(int rgb) {
    var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
    image.setRGB(0, 0, rgb);
    var out = new ByteArrayOutputStream();
    try {
      ImageIO.write(image, "png", out);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }

  private RedactedContent twoPagesWithImages(byte[] first, byte[] second) {
    return RedactedContentFixtures.withImages(
        RedactedContentFixtures.of(
            documentId, List.of("Invoice INV-TOKEN-1", "Grand Total 1180.00")),
        Arrays.asList(first, second));
  }

  private static ExtractionRequest<Totals> request(ExtractionStrategy strategy) {
    return new PromptLoader()
        .request("local-vision", PROMPT, Map.of("currency", "INR"), Totals.class, strategy);
  }

  private static void stubValid() {
    stubChat(
        Scenario.STARTED,
        null,
        chatResponse("{\"invoiceNumber\":\"INV-TOKEN-1\",\"grandTotal\":1180.00}", 3000, 50));
  }

  @Test
  void extract_vision_sendsMaskedPageImagesWithoutText() {
    byte[] first = png(0x000000);
    byte[] second = png(0xFFFFFF);
    stubValid();

    LlmResult<Totals> result =
        gateway.extract(twoPagesWithImages(first, second), request(ExtractionStrategy.VISION));

    assertThat(result).isInstanceOf(LlmResult.Succeeded.class);
    assertThat(recorded)
        .singleElement()
        .extracting(LlmCall::modelId)
        .isEqualTo("example-local-vl-7b");
    wireMock.verify(
        postRequestedFor(urlEqualTo("/api/chat"))
            .withRequestBody(
                matchingJsonPath(
                    "$.messages[1].images[0]", equalTo(Base64.getEncoder().encodeToString(first))))
            .withRequestBody(
                matchingJsonPath(
                    "$.messages[1].images[1]", equalTo(Base64.getEncoder().encodeToString(second))))
            .withRequestBody(
                matchingJsonPath(
                    "$.messages[1].content",
                    equalTo(
                        "Attached are 2 page image(s) in page order: image 1 is page 1, image 2"
                            + " is page 2.")))
            .withRequestBody(notContaining("[p1:l1]")));
  }

  @Test
  void extract_hybrid_sendsMarkedTextAndImages() {
    byte[] first = png(0x000000);
    byte[] second = png(0xFFFFFF);
    stubValid();

    LlmResult<Totals> result =
        gateway.extract(twoPagesWithImages(first, second), request(ExtractionStrategy.HYBRID));

    assertThat(result).isInstanceOf(LlmResult.Succeeded.class);
    wireMock.verify(
        postRequestedFor(urlEqualTo("/api/chat"))
            .withRequestBody(
                matchingJsonPath(
                    "$.messages[1].content",
                    containing("[p1:l1] Invoice INV-TOKEN-1\n[p2:l1] Grand Total 1180.00\n\n")))
            .withRequestBody(
                matchingJsonPath("$.messages[1].content", containing("image 2 is page 2")))
            .withRequestBody(
                matchingJsonPath(
                    "$.messages[1].images[1]",
                    equalTo(Base64.getEncoder().encodeToString(second)))));
  }

  @Test
  void extract_text_sendsNoImagesEvenWhenPagesHaveThem() {
    byte[] image = png(0x123456);
    stubValid();

    gateway.extract(twoPagesWithImages(image, image), request(ExtractionStrategy.TEXT));

    wireMock.verify(
        postRequestedFor(urlEqualTo("/api/chat"))
            .withRequestBody(containing("[p2:l1] Grand Total 1180.00"))
            .withRequestBody(notContaining(Base64.getEncoder().encodeToString(image))));
  }

  @Test
  void extract_visionPageWithoutMaskedImage_isRejectedBeforeAnyCall() {
    RedactedContent missing = twoPagesWithImages(png(0), null);

    assertThatThrownBy(() -> gateway.extract(missing, request(ExtractionStrategy.VISION)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("page 2 has no redacted image");
    assertThat(recorded).isEmpty();
    wireMock.verify(0, postRequestedFor(urlEqualTo("/api/chat")));
  }

  @Test
  void extract_visionOnTextOnlyModel_isRejected() {
    var vision =
        new ExtractionRequest<>("local-text", PROMPT, "x", Totals.class, ExtractionStrategy.VISION);

    assertThatThrownBy(() -> gateway.extract(content, vision))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not support strategy VISION");
    assertThat(recorded).isEmpty();
  }
}
