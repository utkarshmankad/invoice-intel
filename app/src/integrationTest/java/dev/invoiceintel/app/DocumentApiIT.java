package dev.invoiceintel.app;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static dev.invoiceintel.app.TestAdminConfiguration.ADMIN_PASSWORD;
import static dev.invoiceintel.app.TestAdminConfiguration.ADMIN_USERNAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.common.fixtures.InvoiceType;
import dev.invoiceintel.common.fixtures.SyntheticInvoice;
import dev.invoiceintel.common.fixtures.SyntheticInvoiceGenerator;
import dev.invoiceintel.ingest.IngestService;
import dev.invoiceintel.pipeline.PipelineService;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Document API list, detail and reprocess (UC1, UC2, NFR-5, architecture.md §8) over HTTP, with
 * synthetic invoices run through the real stages and a stubbed model (testing.md). Every detail and
 * list response is checked for the invoice's PII values and for raw-text, file, image and vault
 * fields.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
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
class DocumentApiIT {

  private static final Duration TIMEOUT = Duration.ofSeconds(90);
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final WireMockServer WIREMOCK = new WireMockServer(wireMockConfig().dynamicPort());
  private static final List<String> DETAIL_FIELDS =
      List.of("id", "status", "documentClass", "failure", "extraction", "decision", "rules");

  @LocalServerPort int port;
  @Autowired IngestService ingest;
  @Autowired PipelineService pipeline;
  @Autowired JdbcClient jdbc;

  private final HttpClient http = HttpClient.newHttpClient();

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
  void stubModel() throws IOException {
    WIREMOCK.resetAll();
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
                                Map.of("role", "assistant", "content", extractionJson()),
                                "done",
                                true,
                                "prompt_eval_count",
                                1000,
                                "eval_count",
                                200)))));
  }

  @Test
  void get_decidedDocument_returnsDecisionRulesAndPseudonymizedExtractionOnly() throws Exception {
    SyntheticInvoice invoice = SyntheticInvoiceGenerator.generate(InvoiceType.GST_TAX_INVOICE, 81);
    DocumentId id = run(invoice);
    awaitStatus(id, "NEEDS_REVIEW");

    var response = get("/api/documents/" + id.value());

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = JSON.readTree(response.body());
    assertThat(fieldNames(body)).containsExactlyInAnyOrderElementsOf(DETAIL_FIELDS);
    assertThat(body.get("status").asString()).isEqualTo("NEEDS_REVIEW");
    assertThat(body.get("documentClass").asString()).isEqualTo("TAX_INVOICE");
    assertThat(body.get("failure").isNull()).isTrue();
    assertThat(body.get("decision").get("outcome").asString()).isEqualTo("NEEDS_REVIEW");
    assertThat(body.get("decision").get("reasons").findValuesAsString("code"))
        .contains("RULE_WARN", "LOW_CONFIDENCE");
    assertThat(body.get("rules").findValuesAsString("ruleId"))
        .containsExactly("VAL-1", "VAL-2", "VAL-3", "VAL-4", "VAL-5", "VAL-6", "VAL-7");
    // Tokens from the stub stand in for the invoice and order numbers (specs.md §6).
    assertThat(body.get("extraction").get("invoiceNumber").get("value").asString())
        .isEqualTo("INVNO_TOKEN_01");
    assertThat(body.get("extraction").get("grandTotal").get("value").decimalValue())
        .isEqualByComparingTo("118.00");
    assertNoRawContent(response.body(), invoice, id);
  }

  @Test
  void list_responses_containNoRawContent() throws Exception {
    SyntheticInvoice invoice = SyntheticInvoiceGenerator.generate(InvoiceType.GST_TAX_INVOICE, 82);
    DocumentId id = run(invoice);
    awaitStatus(id, "NEEDS_REVIEW");

    var all = get("/api/documents");
    var inReview = get("/api/documents?status=NEEDS_REVIEW");
    var approved = get("/api/documents?status=AUTO_APPROVED");

    assertThat(JSON.readTree(inReview.body()).findValuesAsString("id"))
        .contains(id.value().toString());
    assertThat(JSON.readTree(approved.body()).findValuesAsString("id"))
        .doesNotContain(id.value().toString());
    for (var response : List.of(all, inReview)) {
      assertThat(response.statusCode()).isEqualTo(200);
      for (JsonNode item : JSON.readTree(response.body())) {
        assertThat(fieldNames(item)).containsExactlyInAnyOrder("id", "status");
      }
      assertNoRawContent(response.body(), invoice, id);
    }
  }

  @Test
  void list_unknownStatus_is400ProblemDetail() throws Exception {
    var response = get("/api/documents?status=NOT_A_STATE");

    assertThat(response.statusCode()).isEqualTo(400);
    assertProblem(response);
  }

  @Test
  void get_receivedDocument_hasNoExtractionOrDecision() throws Exception {
    DocumentId id = ingestOnly(83);

    JsonNode body = JSON.readTree(get("/api/documents/" + id.value()).body());

    assertThat(body.get("status").asString()).isEqualTo("RECEIVED");
    assertThat(body.get("documentClass").isNull()).isTrue();
    assertThat(body.get("extraction").isNull()).isTrue();
    assertThat(body.get("decision").isNull()).isTrue();
    assertThat(body.get("rules")).isEmpty();
  }

  @Test
  void get_unknownDocument_is404ProblemDetail() throws Exception {
    var response = get("/api/documents/" + UUID.randomUUID());

    assertThat(response.statusCode()).isEqualTo(404);
    assertProblem(response);
  }

  @Test
  void reprocess_fromValidate_isAcceptedAndRunsTheStageAgain() throws Exception {
    DocumentId id = run(SyntheticInvoiceGenerator.generate(InvoiceType.GST_TAX_INVOICE, 84));
    awaitStatus(id, "NEEDS_REVIEW");

    var response = reprocess(id.value(), "VALIDATE");

    assertThat(response.statusCode()).isEqualTo(202);
    JsonNode body = JSON.readTree(response.body());
    assertThat(body.get("id").asString()).isEqualTo(id.value().toString());
    assertThat(body.get("fromStage").asString()).isEqualTo("VALIDATE");
    await().atMost(TIMEOUT).until(() -> runs(id).contains("VALIDATE 2 SUCCEEDED"));
    awaitStatus(id, "NEEDS_REVIEW");
    JsonNode detail = JSON.readTree(get("/api/documents/" + id.value()).body());
    assertThat(detail.get("decision").get("outcome").asString()).isEqualTo("NEEDS_REVIEW");
  }

  @Test
  void get_failedAfterReprocess_showsFailureAndNoSupersededOutputs() throws Exception {
    SyntheticInvoice invoice = SyntheticInvoiceGenerator.generate(InvoiceType.GST_TAX_INVOICE, 85);
    DocumentId id = run(invoice);
    awaitStatus(id, "NEEDS_REVIEW");
    jdbc.sql("DELETE FROM extraction WHERE document_id = ?").param(id.value()).update();

    assertThat(reprocess(id.value(), "VALIDATE").statusCode()).isEqualTo(202);
    awaitStatus(id, "FAILED");

    var response = get("/api/documents/" + id.value());
    JsonNode body = JSON.readTree(response.body());
    assertThat(body.get("failure").get("stage").asString()).isEqualTo("VALIDATE");
    assertThat(body.get("failure").get("code").asString()).isEqualTo("EXTRACTION_MISSING");
    // The decision of the superseded Validate run is no longer current.
    assertThat(body.get("decision").isNull()).isTrue();
    assertThat(body.get("extraction").isNull()).isTrue();
    assertThat(body.get("rules")).isEmpty();
    assertNoRawContent(response.body(), invoice, id);
  }

  @Test
  void reprocess_stageNotReached_is409ProblemDetail() throws Exception {
    DocumentId id = ingestOnly(86);

    var response = reprocess(id.value(), "VALIDATE");

    assertThat(response.statusCode()).isEqualTo(409);
    assertProblem(response);
    assertThat(JSON.readTree(response.body()).get("detail").asString())
        .isEqualTo("document in RECEIVED has not reached stage VALIDATE");
  }

  @Test
  void reprocess_unknownDocument_is404() throws Exception {
    var response = reprocess(UUID.randomUUID(), "TEXT");

    assertThat(response.statusCode()).isEqualTo(404);
    assertProblem(response);
  }

  @Test
  void reprocess_unknownOrMissingStage_is400() throws Exception {
    DocumentId id = ingestOnly(87);

    assertThat(reprocess(id.value(), "NOT_A_STAGE").statusCode()).isEqualTo(400);
    assertThat(send(postRequest("/api/documents/" + id.value() + "/reprocess")).statusCode())
        .isEqualTo(400);
  }

  @Test
  void reprocess_anonymous_isRejected() throws Exception {
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:"
                        + port
                        + "/api/documents/"
                        + UUID.randomUUID()
                        + "/reprocess?fromStage=TEXT"))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();

    assertThat(send(request).statusCode()).isEqualTo(401);
  }

  /**
   * No buyer PII or real document numbers (pii.md), no text-layer, span, vault or blob fields, and
   * no object-storage keys.
   */
  private void assertNoRawContent(String body, SyntheticInvoice invoice, DocumentId id) {
    for (String value : invoice.piiValues()) {
      assertThat(body).doesNotContain(value);
    }
    JsonNode tree = JSON.readTree(body);
    for (String field :
        List.of("layer", "words", "pages", "text", "ciphertext", "token", "bucket", "key")) {
      assertThat(tree.findValues(field)).as("field %s", field).isEmpty();
    }
    assertThat(body).doesNotContain("raw/" + id.value()).doesNotContain("redacted/");
  }

  private static List<String> fieldNames(JsonNode node) {
    var names = new ArrayList<String>();
    node.propertyNames().forEach(names::add);
    return names;
  }

  private static void assertProblem(HttpResponse<String> response) {
    assertThat(response.headers().firstValue("Content-Type"))
        .hasValueSatisfying(type -> assertThat(type).startsWith("application/problem+json"));
  }

  private static String extractionJson() throws IOException {
    try (InputStream in =
        DocumentApiIT.class.getResourceAsStream("/fixtures/llm/invoice-extraction-response.json")) {
      if (in == null) {
        throw new IOException("fixture missing");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private DocumentId run(SyntheticInvoice invoice) {
    DocumentId id =
        ingest.ingest(List.of(SyntheticInvoiceGenerator.toPdf(invoice))).getFirst().documentId();
    pipeline.start(id);
    return id;
  }

  /** A document left in RECEIVED: ingested without starting the pipeline. */
  private DocumentId ingestOnly(long seed) {
    var invoice = SyntheticInvoiceGenerator.generate(InvoiceType.GST_TAX_INVOICE, seed);
    return ingest.ingest(List.of(SyntheticInvoiceGenerator.toPdf(invoice))).getFirst().documentId();
  }

  private List<String> runs(DocumentId id) {
    return jdbc.sql(
            "SELECT stage || ' ' || run_no || ' ' || status FROM stage_run"
                + " WHERE document_id = ? ORDER BY stage, run_no")
        .param(id.value())
        .query(String.class)
        .list();
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

  private HttpResponse<String> get(String path) throws IOException, InterruptedException {
    return send(authenticated(path).GET().build());
  }

  private HttpResponse<String> reprocess(UUID id, String fromStage)
      throws IOException, InterruptedException {
    return send(postRequest("/api/documents/" + id + "/reprocess?fromStage=" + fromStage));
  }

  private HttpRequest postRequest(String path) {
    return authenticated(path).POST(HttpRequest.BodyPublishers.noBody()).build();
  }

  private HttpRequest.Builder authenticated(String path) {
    var credentials = (ADMIN_USERNAME + ":" + ADMIN_PASSWORD).getBytes(StandardCharsets.UTF_8);
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials));
  }

  private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
