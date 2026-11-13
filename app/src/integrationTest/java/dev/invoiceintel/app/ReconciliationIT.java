package dev.invoiceintel.app;

import static dev.invoiceintel.app.TestAdminConfiguration.ADMIN_PASSWORD;
import static dev.invoiceintel.app.TestAdminConfiguration.ADMIN_USERNAME;
import static org.assertj.core.api.Assertions.assertThat;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.reconcile.ImportId;
import dev.invoiceintel.reconcile.OrderHistoryItem;
import dev.invoiceintel.reconcile.OrderHistoryStore;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reconciliation run and report over HTTP (REC-2..REC-4, architecture.md §6.7, §8, ADR-26) with
 * synthetic documents, extractions and order-history rows written straight into the database. Order
 * tokens are made-up strings, not real tokens of real order numbers.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({
  TestcontainersConfiguration.class,
  TestAdminConfiguration.class,
  TestPiiKeysConfiguration.class
})
class ReconciliationIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final LocalDate ORDERED = LocalDate.of(2025, 1, 10);

  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;

  private final HttpClient http = HttpClient.newHttpClient();
  private int sha;

  @BeforeEach
  void emptyTables() {
    jdbc.sql("DELETE FROM reconciliation_match").update();
    jdbc.sql("DELETE FROM document").update();
    jdbc.sql("DELETE FROM order_history_item").update();
  }

  @Test
  void report_beforeAnyRun_hasZeroCountsAndNoRate() throws Exception {
    var response = send("GET", "/api/reconciliation", true);

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = JSON.readTree(response.body());
    assertThat(body.get("documents").asInt()).isZero();
    assertThat(body.get("matchRate").isNull()).isTrue();
    assertThat(body.get("orders").get("MATCHED").asInt()).isZero();
    assertThat(body.get("rows")).isEmpty();
  }

  @Test
  void run_syntheticScenarios_storesRowsAndReportsMatchRate() throws Exception {
    // MATCHED: the latest Extract run counts, not the stale first one.
    var matched = document("AUTO_APPROVED");
    extraction(matched, 1, "TAX_INVOICE", "TOK-ORDER-1", "2025-01-12", "999.00");
    extraction(matched, 2, "TAX_INVOICE", "TOK-ORDER-1", "2025-01-12", "100.00");
    // AMOUNT_MISMATCH: invoice 50 vs order 70.
    var mismatch = document("NEEDS_REVIEW");
    extraction(mismatch, 1, "TAX_INVOICE", "TOK-ORDER-2", "2025-01-11", "50.00");
    // INVOICE_WITHOUT_ORDER: no order number.
    var noOrder = document("AUTO_APPROVED");
    extraction(noOrder, 1, "TAX_INVOICE", null, "2025-01-11", "10.00");
    // Not decided yet: not reconciled, so its order stays ORDER_WITHOUT_INVOICE.
    var pending = document("EXTRACTED");
    extraction(pending, 1, "TAX_INVOICE", "TOK-ORDER-3", "2025-01-11", "30.00");
    new OrderHistoryStore(jdbc)
        .insert(
            ImportId.random(),
            List.of(
                item("TOK-ORDER-1", "100.00"),
                item("TOK-ORDER-2", "70.00"),
                item("TOK-ORDER-3", "30.00")));

    var run = send("POST", "/api/reconciliation/run", true);

    assertThat(run.statusCode()).isEqualTo(200);
    JsonNode body = JSON.readTree(run.body());
    assertThat(body.get("orders").get("MATCHED").asInt()).isEqualTo(1);
    assertThat(body.get("orders").get("AMOUNT_MISMATCH").asInt()).isEqualTo(1);
    assertThat(body.get("orders").get("DATE_MISMATCH").asInt()).isZero();
    assertThat(body.get("orders").get("INVOICE_WITHOUT_ORDER").asInt()).isEqualTo(1);
    assertThat(body.get("orders").get("ORDER_WITHOUT_INVOICE").asInt()).isEqualTo(1);
    assertThat(body.get("documents").asInt()).isEqualTo(3);
    assertThat(body.get("matchedDocuments").asInt()).isEqualTo(1);
    assertThat(body.get("matchRate").asDouble()).isEqualTo(1.0 / 3);
    JsonNode rows = body.get("rows");
    assertThat(rows).hasSize(4);
    assertThat(rows.get(0).get("orderToken").asString()).isEqualTo("TOK-ORDER-1");
    assertThat(rows.get(0).get("invoiceIds").get(0).asString())
        .isEqualTo(matched.value().toString());
    assertThat(rows.get(1).get("amountDiff").decimalValue())
        .isEqualByComparingTo(new BigDecimal("-20.00"));
    assertThat(rows.get(3).get("orderToken").isNull()).isTrue();
    assertThat(rows.get(3).get("status").asString()).isEqualTo("INVOICE_WITHOUT_ORDER");

    // The report reads the stored rows; a second run replaces them.
    var report = send("GET", "/api/reconciliation", true);
    assertThat(JSON.readTree(report.body())).isEqualTo(body);
    send("POST", "/api/reconciliation/run", true);
    assertThat(jdbc.sql("SELECT count(*) FROM reconciliation_match").query(Long.class).single())
        .isEqualTo(4);
  }

  @Test
  void endpoints_withoutLogin_are401() throws Exception {
    assertThat(send("GET", "/api/reconciliation", false).statusCode()).isEqualTo(401);
    assertThat(send("POST", "/api/reconciliation/run", false).statusCode()).isEqualTo(401);
  }

  private DocumentId document(String status) {
    var id = DocumentId.random();
    jdbc.sql(
            "INSERT INTO document (id, sha256, mime_type, status)"
                + " VALUES (?, ?, 'application/pdf', ?)")
        .params(id.value(), String.format("%064x", ++sha), status)
        .update();
    return id;
  }

  private void extraction(
      DocumentId id, int runNo, String documentClass, String token, String date, String total) {
    jdbc.sql(
            "INSERT INTO stage_run (id, document_id, stage, run_no, status, started_at, ended_at)"
                + " VALUES (?, ?, 'EXTRACT', ?, 'SUCCEEDED', now(), now())")
        .params(UUID.randomUUID(), id.value(), runNo)
        .update();
    var result = JSON.createObjectNode();
    result.set("documentClass", field(documentClass));
    if (token != null) {
      result.set("orderNumber", field(token));
    }
    result.set("invoiceDate", field(date));
    result.set(
        "grandTotal",
        JSON.createObjectNode()
            .put("value", new BigDecimal(total))
            .put("confidence", 0.9)
            .put("source", "LLM"));
    jdbc.sql(
            "INSERT INTO extraction"
                + " (id, document_id, run_no, strategy, model_id, prompt_version, result)"
                + " VALUES (?, ?, ?, 'TEXT', 'example-local-8b', 'invoice-extraction/v1',"
                + " CAST(? AS JSONB))")
        .params(UUID.randomUUID(), id.value(), runNo, JSON.writeValueAsString(result))
        .update();
  }

  private static JsonNode field(String value) {
    return JSON.createObjectNode().put("value", value).put("confidence", 0.9).put("source", "LLM");
  }

  private static OrderHistoryItem item(String token, String total) {
    return new OrderHistoryItem(token, ORDERED, null, null, 1, null, new BigDecimal(total));
  }

  private HttpResponse<String> send(String method, String path, boolean authenticated)
      throws IOException, InterruptedException {
    var builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .method(method, HttpRequest.BodyPublishers.noBody());
    if (authenticated) {
      var credentials = (ADMIN_USERNAME + ":" + ADMIN_PASSWORD).getBytes(StandardCharsets.UTF_8);
      builder.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials));
    }
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }
}
