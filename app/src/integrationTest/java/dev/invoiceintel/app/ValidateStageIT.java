package dev.invoiceintel.app;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.common.fixtures.FakeIds;
import dev.invoiceintel.common.fixtures.GstState;
import dev.invoiceintel.common.fixtures.InvoiceType;
import dev.invoiceintel.common.fixtures.SyntheticInvoice;
import dev.invoiceintel.common.fixtures.SyntheticInvoiceGenerator;
import dev.invoiceintel.ingest.IngestService;
import dev.invoiceintel.pipeline.PipelineService;
import dev.invoiceintel.pipeline.Stage;
import dev.invoiceintel.pipeline.ValidateStageHandler;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;
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
 * The whole per-document pipeline, RECEIVED → decision (architecture.md §5.1, §6.6, ADR-20): a
 * synthetic invoice runs through the real Text, PII, Extract and Validate stages with a stubbed
 * model, and the Validate stage stores the rule results and the VAL-8 decision and moves the
 * document to AUTO_APPROVED or NEEDS_REVIEW. Model responses are hand-written from the schema
 * (testing.md); the model is the local Ollama provider pointed at WireMock.
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
class ValidateStageIT {

  private static final Duration TIMEOUT = Duration.ofSeconds(90);
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final WireMockServer WIREMOCK = new WireMockServer(wireMockConfig().dynamicPort());
  private static final List<String> RULES =
      List.of("VAL-1", "VAL-2", "VAL-3", "VAL-4", "VAL-5", "VAL-6", "VAL-7");

  @Autowired IngestService ingest;
  @Autowired PipelineService pipeline;
  @Autowired JdbcClient jdbc;
  @Autowired MeterRegistry meters;

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
  void consistentConfidentExtraction_endsAutoApproved() throws IOException {
    SyntheticInvoice invoice = SyntheticInvoiceGenerator.generate(InvoiceType.GST_TAX_INVOICE, 91);
    stubModel(approvableExtractionJson());
    double approved = count("invoice.decision", "outcome", "AUTO_APPROVED");
    double tokens = count("invoice.llm.tokens", "direction", "input");
    double spans = count("invoice.pii.spans", "type", "PERSON");
    long validated = stageRuns("VALIDATE", "SUCCEEDED");

    DocumentId id = run(invoice);

    awaitStatus(id, "AUTO_APPROVED");
    assertThat(runs(id))
        .containsExactly(
            "EXTRACT 1 SUCCEEDED", "PII 1 SUCCEEDED", "TEXT 1 SUCCEEDED", "VALIDATE 1 SUCCEEDED");
    assertThat(ruleOutcomes(id))
        .containsOnlyKeys(RULES)
        .containsValue("PASS")
        .doesNotContainValue("FAIL");
    Map<String, Object> decision = decision(id);
    assertThat(decision).containsEntry("outcome", "AUTO_APPROVED").containsEntry("reasons", 0);
    // architecture.md §12 (NFR-6); metrics are reported just after the commit.
    await()
        .atMost(TIMEOUT)
        .untilAsserted(
            () -> {
              assertThat(count("invoice.decision", "outcome", "AUTO_APPROVED"))
                  .isGreaterThanOrEqualTo(approved + 1);
              assertThat(stageRuns("VALIDATE", "SUCCEEDED")).isGreaterThan(validated);
              assertThat(count("invoice.llm.tokens", "direction", "input"))
                  .isGreaterThanOrEqualTo(tokens + 1000);
              assertThat(count("invoice.pii.spans", "type", "PERSON")).isGreaterThan(spans);
            });
  }

  @Test
  void extractionWithWarningsAndLowConfidence_endsInReviewWithReasons() throws IOException {
    SyntheticInvoice invoice = SyntheticInvoiceGenerator.generate(InvoiceType.GST_TAX_INVOICE, 92);
    // The shared fixture has no seller GSTIN, address or place of supply (VAL-4, VAL-5 warn) and
    // some fields at 0.8 confidence.
    stubModel(extractionJson());

    DocumentId id = run(invoice);

    awaitStatus(id, "NEEDS_REVIEW");
    assertThat(ruleOutcomes(id)).containsOnlyKeys(RULES).containsEntry("VAL-4", "WARN");
    assertThat(decision(id)).containsEntry("outcome", "NEEDS_REVIEW");
    assertThat(reasonCodes(id)).contains("RULE_WARN", "LOW_CONFIDENCE");
  }

  @Test
  void reprocessFromValidate_replacesResultsAndDecision() throws IOException {
    SyntheticInvoice invoice = SyntheticInvoiceGenerator.generate(InvoiceType.GST_TAX_INVOICE, 93);
    stubModel(extractionJson());
    DocumentId id = run(invoice);
    awaitStatus(id, "NEEDS_REVIEW");

    assertThat(pipeline.reprocess(id, Stage.VALIDATE)).isTrue();

    await().atMost(TIMEOUT).until(() -> runs(id).contains("VALIDATE 2 SUCCEEDED"));
    awaitStatus(id, "NEEDS_REVIEW");
    assertThat(runs(id)).contains("VALIDATE 1 SUPERSEDED");
    assertThat(ruleOutcomes(id)).hasSize(RULES.size());
    assertThat(count("decision", id)).isEqualTo(1);
  }

  @Test
  void missingExtraction_failsValidateWithExtractionMissing() throws IOException {
    SyntheticInvoice invoice = SyntheticInvoiceGenerator.generate(InvoiceType.GST_TAX_INVOICE, 94);
    stubModel(extractionJson());
    DocumentId id = run(invoice);
    awaitStatus(id, "NEEDS_REVIEW");
    jdbc.sql("DELETE FROM extraction WHERE document_id = ?").param(id.value()).update();
    double failedDecisions = count("invoice.decision", "outcome", "FAILED");
    long failedRuns = stageRuns("VALIDATE", "FAILED");

    pipeline.reprocess(id, Stage.VALIDATE);

    awaitStatus(id, "FAILED");
    assertThat(
            jdbc.sql("SELECT failed_stage || ' ' || failure_code FROM document WHERE id = ?")
                .param(id.value())
                .query(String.class)
                .single())
        .isEqualTo("VALIDATE " + ValidateStageHandler.EXTRACTION_MISSING);
    // A missing extraction is the FAILED decision of §6.6.
    await()
        .atMost(TIMEOUT)
        .untilAsserted(
            () -> {
              assertThat(count("invoice.decision", "outcome", "FAILED"))
                  .isGreaterThanOrEqualTo(failedDecisions + 1);
              assertThat(stageRuns("VALIDATE", "FAILED")).isGreaterThan(failedRuns);
            });
  }

  /** Sum of the counters named {@code name} with the given tag, 0 before the first event. */
  private double count(String name, String tag, String value) {
    return meters.find(name).tag(tag, value).counters().stream().mapToDouble(Counter::count).sum();
  }

  /** Runs recorded by the {@code invoice.stage.duration} timer for one stage and outcome. */
  private long stageRuns(String stage, String outcome) {
    return meters
        .find("invoice.stage.duration")
        .tag("stage", stage)
        .tag("outcome", outcome)
        .timers()
        .stream()
        .mapToLong(Timer::count)
        .sum();
  }

  private static String extractionJson() throws IOException {
    try (InputStream in =
        ValidateStageIT.class.getResourceAsStream(
            "/fixtures/llm/invoice-extraction-response.json")) {
      if (in == null) {
        throw new IOException("fixture missing");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /**
   * The shared fixture with every confidence at 0.95 and the seller fields VAL-4 and VAL-5 need: a
   * checksum-valid Karnataka GSTIN (generated, never a literal) and an inter-state supply, which
   * matches the fixture's IGST line.
   */
  private static String approvableExtractionJson() throws IOException {
    String gstin = FakeIds.gstin(GstState.KARNATAKA, FakeIds.pan(new Random(95), 'C', 'E'), '1');
    String sellerFields =
        """
        {
          "sellerGstin": {"value": "%s", "confidence": 0.95, "source": "LLM"},
          "sellerAddress": {"value": "Plot 4, Industrial Area, Bengaluru, Karnataka",
                            "confidence": 0.95, "source": "LLM"},
          "placeOfSupply": {"value": "Maharashtra", "confidence": 0.95, "source": "LLM"},
        """
            .formatted(gstin);
    return extractionJson()
        .replaceAll("\"confidence\": [0-9.]+", "\"confidence\": 0.95")
        .replaceFirst("\\{", sellerFields);
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

  private List<String> runs(DocumentId id) {
    return jdbc.sql(
            "SELECT stage || ' ' || run_no || ' ' || status FROM stage_run"
                + " WHERE document_id = ? ORDER BY stage, run_no")
        .param(id.value())
        .query(String.class)
        .list();
  }

  /** Rule ID → outcome for the document's current extraction. */
  private Map<String, String> ruleOutcomes(DocumentId id) {
    return jdbc
        .sql(
            """
            SELECT v.rule_id, v.outcome FROM validation_result v
            JOIN extraction e ON e.id = v.extraction_id
            WHERE e.document_id = ?
            """)
        .param(id.value())
        .query((rs, row) -> Map.entry(rs.getString("rule_id"), rs.getString("outcome")))
        .list()
        .stream()
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  private Map<String, Object> decision(DocumentId id) {
    return jdbc.sql(
            "SELECT outcome, jsonb_array_length(reasons) AS reasons FROM decision"
                + " WHERE document_id = ?")
        .param(id.value())
        .query()
        .singleRow();
  }

  private List<String> reasonCodes(DocumentId id) {
    return jdbc.sql(
            "SELECT r->>'code' FROM decision, jsonb_array_elements(reasons) r"
                + " WHERE document_id = ?")
        .param(id.value())
        .query(String.class)
        .list();
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
}
