package dev.invoiceintel.app;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.notContaining;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.common.fixtures.InvoiceType;
import dev.invoiceintel.common.fixtures.SyntheticInvoice;
import dev.invoiceintel.common.fixtures.SyntheticInvoiceGenerator;
import dev.invoiceintel.ingest.IngestService;
import dev.invoiceintel.pii.LeakChecker;
import dev.invoiceintel.pii.PiiType;
import dev.invoiceintel.pipeline.PipelineService;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Extract stage (architecture.md §5.2, §6.5) behind the real Text and PII stages: a synthetic
 * invoice reaches EXTRACTED with a stubbed model, and the leak check (PII-6, §6.3) runs on the
 * model's raw response against the vault entries the PII stage stored. The model is the local
 * Ollama provider pointed at WireMock; responses are hand-written from the schema (testing.md).
 */
@SpringBootTest(
    properties = {
      "jobrunr.background-job-server.enabled=true",
      "jobrunr.background-job-server.poll-interval-in-seconds=5",
      "invoice-intel.llm.extraction.model=local"
    })
@ActiveProfiles("test")
@Import({
  TestcontainersConfiguration.class,
  TestAdminConfiguration.class,
  TestPiiKeysConfiguration.class
})
class ExtractStageIT {

  private static final Duration TIMEOUT = Duration.ofSeconds(90);
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final WireMockServer WIREMOCK = new WireMockServer(wireMockConfig().dynamicPort());

  @Autowired IngestService ingest;
  @Autowired PipelineService pipeline;
  @Autowired LeakChecker leakChecker;
  @Autowired MeterRegistry meters;
  @Autowired JdbcClient jdbc;

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
  void validResponse_reachesExtractedWithExtractionAndCallRows() throws IOException {
    SyntheticInvoice invoice = SyntheticInvoiceGenerator.generate(InvoiceType.GST_TAX_INVOICE, 81);
    stubModel(extractionJson());

    DocumentId id = run(invoice);

    awaitStatus(id, "EXTRACTED");
    Map<String, Object> row =
        jdbc.sql(
                "SELECT strategy, prompt_version, result->'grandTotal'->>'value' AS total"
                    + " FROM extraction WHERE document_id = ?")
            .param(id.value())
            .query()
            .singleRow();
    assertThat(row)
        .containsEntry("strategy", "TEXT")
        .containsEntry("prompt_version", "invoice-extraction/v1")
        .containsEntry("total", "118.00");
    assertThat(
            jdbc.sql("SELECT outcome FROM llm_call WHERE document_id = ?")
                .param(id.value())
                .query(String.class)
                .list())
        .containsExactly("SUCCEEDED");
    // The model got the line-marked redacted text, never the buyer's name (PII-1).
    WIREMOCK.verify(
        postRequestedFor(urlEqualTo("/api/chat"))
            .withRequestBody(notContaining(invoice.buyer().name())));
  }

  @Test
  void leakingResponse_failsDocumentWithPiiLeakAndStoresNothing() throws IOException {
    SyntheticInvoice invoice = SyntheticInvoiceGenerator.generate(InvoiceType.GST_TAX_INVOICE, 71);
    double before = leakCount();
    // A valid extraction that copies the buyer's name: it parses, but must not be stored.
    stubModel(extractionJson().replace("Example Traders", invoice.buyer().name().toUpperCase()));

    DocumentId id = run(invoice);

    awaitStatus(id, "FAILED");
    assertThat(failure(id)).isEqualTo("EXTRACT " + LeakChecker.FAILURE_CODE);
    assertThat(leakCount()).isEqualTo(before + 1);
    assertThat(count("extraction", id)).isZero();
  }

  @Test
  void invalidResponseTwice_failsWithInvalidOutput() {
    SyntheticInvoice invoice = SyntheticInvoiceGenerator.generate(InvoiceType.GST_TAX_INVOICE, 82);
    stubModel("{\"grandTotal\": null}");

    DocumentId id = run(invoice);

    awaitStatus(id, "FAILED");
    assertThat(failure(id)).isEqualTo("EXTRACT LLM_INVALID_OUTPUT");
    WIREMOCK.verify(2, postRequestedFor(urlEqualTo("/api/chat")));
  }

  @Test
  void check_readsOnlyTheDocumentsOwnVaultValues() throws IOException {
    SyntheticInvoice first = SyntheticInvoiceGenerator.generate(InvoiceType.GST_TAX_INVOICE, 73);
    SyntheticInvoice second = SyntheticInvoiceGenerator.generate(InvoiceType.CREDIT_NOTE, 74);
    stubModel(extractionJson());
    DocumentId firstId = run(first);
    DocumentId secondId = run(second);
    awaitStatus(firstId, "EXTRACTED");
    awaitStatus(secondId, "EXTRACTED");

    String secondName = second.buyer().name();
    assertThat(leakChecker.check(secondId, "name: " + secondName).leakedTypes())
        .contains(PiiType.PERSON);
    // The first document's vault values don't include the second buyer's name.
    assertThat(first.buyer().name()).isNotEqualTo(secondName);
    assertThat(leakChecker.check(firstId, "name: " + secondName).leaked()).isFalse();
  }

  private static String extractionJson() throws IOException {
    try (InputStream in =
        ExtractStageIT.class.getResourceAsStream(
            "/fixtures/llm/invoice-extraction-response.json")) {
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

  private DocumentId run(SyntheticInvoice invoice) {
    DocumentId id =
        ingest.ingest(List.of(SyntheticInvoiceGenerator.toPdf(invoice))).getFirst().documentId();
    pipeline.start(id);
    return id;
  }

  private double leakCount() {
    var counter = meters.find("invoice.pii.leak").counter();
    return counter == null ? 0 : counter.count();
  }

  private int count(String table, DocumentId id) {
    return jdbc.sql("SELECT count(*) FROM " + table + " WHERE document_id = ?")
        .param(id.value())
        .query(Integer.class)
        .single();
  }

  private void awaitStatus(DocumentId id, String expected) {
    await()
        .atMost(TIMEOUT)
        .until(
            () ->
                jdbc.sql("SELECT status FROM document WHERE id = ?")
                    .param(id.value())
                    .query(String.class)
                    .single()
                    .equals(expected));
  }

  private String failure(DocumentId id) {
    return jdbc.sql("SELECT failed_stage || ' ' || failure_code FROM document WHERE id = ?")
        .param(id.value())
        .query(String.class)
        .single();
  }
}
