package dev.invoiceintel.app;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.invoiceintel.app.eval.EvalBatch;
import dev.invoiceintel.common.fixtures.InvoiceType;
import dev.invoiceintel.common.fixtures.SyntheticInvoiceGenerator;
import dev.invoiceintel.eval.run.DocumentResult;
import dev.invoiceintel.eval.run.LabelledDocument;
import dev.invoiceintel.eval.run.ResultsDocumentRunner;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The eval batch (architecture.md §6.9, ADR-24): synthetic invoices run in-process through the real
 * Text, PII, Extract and Validate stages with a stubbed model, and the result files it writes are
 * the ones {@code eval run --results} reads. No JobRunr server: {@code runNow} runs the chain in
 * the test's thread.
 */
@SpringBootTest(properties = "invoice-intel.llm.extraction.model=local")
@ActiveProfiles("test")
@Import({
  TestcontainersConfiguration.class,
  TestAdminConfiguration.class,
  TestPiiKeysConfiguration.class
})
class EvalBatchIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final WireMockServer WIREMOCK = new WireMockServer(wireMockConfig().dynamicPort());
  private static final Set<String> DECIDED = Set.of("AUTO_APPROVED", "NEEDS_REVIEW");

  @Autowired EvalBatch batch;

  @TempDir Path input;
  @TempDir Path output;

  @DynamicPropertySource
  static void modelServer(DynamicPropertyRegistry registry) {
    if (!WIREMOCK.isRunning()) {
      WIREMOCK.start();
    }
    registry.add("invoice-intel.llm.ollama.base-url", WIREMOCK::baseUrl);
  }

  @AfterAll
  static void stopModelServer() {
    WIREMOCK.stop();
  }

  @BeforeEach
  void resetStubs() {
    WIREMOCK.resetAll();
  }

  @Test
  void run_validModelAnswers_writesDecidedResultPerOriginal() throws IOException {
    stubModel(extractionJson());
    String first = original("a.pdf", 5901);
    String second = original("b.pdf", 5902);

    EvalBatch.Summary summary = batch.run(input, output);

    assertThat(summary).isEqualTo(new EvalBatch.Summary(2, 0));
    for (String sha256 : new String[] {first, second}) {
      JsonNode result = JSON.readTree(Files.readString(output.resolve(sha256 + ".json")));
      assertThat(result.path("sha256").asString()).isEqualTo(sha256);
      assertThat(result.path("status").asString()).isEqualTo("EXTRACTED");
      assertThat(DECIDED).contains(result.path("decision").asString());
      assertThat(result.path("latencyMs").asLong()).isPositive();
      assertThat(result.path("usage").path("modelIds")).isNotEmpty();
      assertThat(result.path("usage").path("promptVersions").get(0).asString())
          .isEqualTo("invoice-extraction/v1");
      // Amounts compare by value: the extraction comes back from JSONB through the details reader.
      assertThat(result.path("extraction").path("grandTotal").path("value").decimalValue())
          .isEqualByComparingTo(new BigDecimal("118.00"));
    }

    // The eval side reads the same files (contract between app and eval).
    DocumentResult replayed =
        new ResultsDocumentRunner(output)
            .run(new LabelledDocument(first, true, JSON.createObjectNode()));
    assertThat(replayed.status()).isEqualTo(DocumentResult.Status.EXTRACTED);
    assertThat(replayed.latencyMs()).isPresent();
    assertThat(replayed.usage().promptVersions()).containsExactly("invoice-extraction/v1");
  }

  @Test
  void run_unparseableModelAnswer_writesFailedResultWithStageAndCode() throws IOException {
    stubModel("not json");
    String sha256 = original("c.pdf", 5903);

    batch.run(input, output);

    JsonNode result = JSON.readTree(Files.readString(output.resolve(sha256 + ".json")));
    assertThat(result.path("status").asString()).isEqualTo("FAILED");
    assertThat(result.path("decision").asString()).isEqualTo("FAILED");
    assertThat(result.path("error").asString()).startsWith("EXTRACT ");
    assertThat(result.path("extraction").isNull()).isTrue();
    DocumentResult replayed =
        new ResultsDocumentRunner(output)
            .run(new LabelledDocument(sha256, true, JSON.createObjectNode()));
    assertThat(replayed.status()).isEqualTo(DocumentResult.Status.FAILED);
    assertThat(replayed.error()).startsWith("EXTRACT ");
  }

  @Test
  void run_originalAlreadyIngested_isSkippedWithoutResult() throws IOException {
    stubModel(extractionJson());
    String sha256 = original("d.pdf", 5904);
    batch.run(input, output);
    Files.delete(output.resolve(sha256 + ".json"));

    EvalBatch.Summary again = batch.run(input, output);

    assertThat(again).isEqualTo(new EvalBatch.Summary(0, 1));
    assertThat(output.resolve(sha256 + ".json")).doesNotExist();
  }

  /** Writes a synthetic invoice into the input folder and returns its SHA-256. */
  private String original(String name, long seed) throws IOException {
    byte[] pdf =
        SyntheticInvoiceGenerator.toPdf(
            SyntheticInvoiceGenerator.generate(InvoiceType.GST_TAX_INVOICE, seed));
    Files.write(input.resolve(name), pdf);
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pdf));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String extractionJson() throws IOException {
    try (InputStream in =
        EvalBatchIT.class.getResourceAsStream("/fixtures/llm/invoice-extraction-response.json")) {
      if (in == null) {
        throw new IOException("fixture missing");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /** Every chat call answers {@code content}, as an Ollama /api/chat response. */
  private static void stubModel(String content) {
    WIREMOCK.stubFor(
        post(urlEqualTo("/api/chat"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        JSON.writeValueAsString(
                            Map.of(
                                "model",
                                "stub",
                                "message",
                                Map.of("role", "assistant", "content", content),
                                "done",
                                true,
                                "prompt_eval_count",
                                1000,
                                "eval_count",
                                200)))));
  }
}
