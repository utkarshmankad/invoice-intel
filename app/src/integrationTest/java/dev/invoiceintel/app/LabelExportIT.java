package dev.invoiceintel.app;

import static dev.invoiceintel.app.TestAdminConfiguration.ADMIN_PASSWORD;
import static dev.invoiceintel.app.TestAdminConfiguration.ADMIN_USERNAME;
import static org.assertj.core.api.Assertions.assertThat;

import dev.invoiceintel.common.DocumentClass;
import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.common.extraction.ChargeType;
import dev.invoiceintel.common.extraction.Currency;
import dev.invoiceintel.common.extraction.Field;
import dev.invoiceintel.common.extraction.InvoiceExtraction;
import dev.invoiceintel.common.extraction.LineItem;
import dev.invoiceintel.common.extraction.TaxLine;
import dev.invoiceintel.common.extraction.TaxType;
import dev.invoiceintel.eval.label.LabelValidator;
import dev.invoiceintel.eval.run.Dataset;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
 * REV-4, ADR-31, architecture.md §6.8: {@code GET /api/labels/export} writes every reviewed
 * document as a pseudonymized label that validates against the M5.1 schema and loads as an eval
 * dataset, with the review corrections applied.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({
  TestcontainersConfiguration.class,
  TestAdminConfiguration.class,
  TestPiiKeysConfiguration.class
})
class LabelExportIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;

  @TempDir Path dir;

  private final HttpClient client = HttpClient.newHttpClient();

  @BeforeEach
  void clear() {
    jdbc.sql("DELETE FROM document").update();
  }

  @Test
  void export_reviewedDocuments_areValidPseudonymizedLabelsWithCorrections() throws Exception {
    var reviewed = document("NEEDS_REVIEW", "a");
    extracted(reviewed, extraction("Example widget"));
    var correct =
        send(patch(reviewed, "{\"fields\": {\"lineItems[0].taxLines[0].rate\": \"12\"}}"));
    assertThat(correct.statusCode()).isEqualTo(200);
    assertThat(send(approve(reviewed)).statusCode()).isEqualTo(200);
    var queued = document("NEEDS_REVIEW", "b");
    extracted(queued, extraction("Example widget"));
    var auto = document("AUTO_APPROVED", "c");
    extracted(auto, extraction("Example widget"));
    var unexportable = document("REVIEWED", "d");
    extracted(unexportable, extraction(" "));
    document("REVIEWED", "e");

    var response = client.send(export(), HttpResponse.BodyHandlers.ofByteArray());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Type")).hasValue("application/zip");
    assertThat(response.headers().firstValue("Content-Disposition"))
        .hasValueSatisfying(v -> assertThat(v).contains("labels-export.zip"));
    assertThat(response.headers().firstValue("Cache-Control"))
        .hasValueSatisfying(v -> assertThat(v).contains("no-store"));
    Map<String, byte[]> entries = unzip(response.body());
    String sha = "a".repeat(64);
    assertThat(entries).containsOnlyKeys(sha + ".json", "SKIPPED.txt");
    assertThat(new String(entries.get("SKIPPED.txt"), StandardCharsets.UTF_8))
        .isEqualTo(
            unexportable.value() + "\t$.fields.lineItems[0].description: must be non-blank text\n");

    JsonNode label = JSON.readTree(entries.get(sha + ".json"));
    assertThat(LabelValidator.validate(label, sha + ".json")).isEmpty();
    assertThat(label.get("pseudonymized").booleanValue()).isTrue();
    assertThat(label.get("piiSpans")).isEmpty();
    assertThat(label.at("/fields/invoiceNumber").stringValue()).isEqualTo("tok_inv_0001");
    assertThat(label.at("/fields/lineItems/0/taxLines/0/rate").stringValue()).isEqualTo("12");
    assertThat(label.at("/fields/grandTotal").stringValue()).isEqualTo("1180.00");

    for (var entry : entries.entrySet()) {
      Files.write(dir.resolve(entry.getKey()), entry.getValue());
    }
    var dataset = Dataset.load(dir);
    assertThat(dataset.invalid()).isEmpty();
    assertThat(dataset.documents())
        .singleElement()
        .satisfies(
            d -> {
              assertThat(d.sha256()).isEqualTo(sha);
              assertThat(d.pseudonymized()).isTrue();
            });
  }

  @Test
  void export_nothingReviewed_isEmptyZip() throws Exception {
    var response = client.send(export(), HttpResponse.BodyHandlers.ofByteArray());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(unzip(response.body())).isEmpty();
  }

  @Test
  void export_withoutCredentials_isUnauthorized() throws Exception {
    var request = HttpRequest.newBuilder(uri("/api/labels/export")).build();

    assertThat(client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode())
        .isEqualTo(401);
  }

  private static Map<String, byte[]> unzip(byte[] zip) throws IOException {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    try (var in = new ZipInputStream(new ByteArrayInputStream(zip))) {
      for (var entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
        entries.put(entry.getName(), in.readAllBytes());
      }
    }
    return entries;
  }

  private HttpResponse<String> send(HttpRequest request) throws Exception {
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpRequest export() {
    return HttpRequest.newBuilder(uri("/api/labels/export"))
        .header("Authorization", basicAuth())
        .build();
  }

  private HttpRequest patch(DocumentId id, String json) {
    return HttpRequest.newBuilder(uri("/api/documents/" + id.value() + "/fields"))
        .header("Authorization", basicAuth())
        .header("Content-Type", "application/json")
        .method("PATCH", HttpRequest.BodyPublishers.ofString(json))
        .build();
  }

  private HttpRequest approve(DocumentId id) {
    return HttpRequest.newBuilder(uri("/api/documents/" + id.value() + "/approve"))
        .header("Authorization", basicAuth())
        .POST(HttpRequest.BodyPublishers.noBody())
        .build();
  }

  private URI uri(String path) {
    return URI.create("http://127.0.0.1:" + port + path);
  }

  private static String basicAuth() {
    return "Basic "
        + Base64.getEncoder()
            .encodeToString(
                (ADMIN_USERNAME + ":" + ADMIN_PASSWORD).getBytes(StandardCharsets.UTF_8));
  }

  private DocumentId document(String status, String shaChar) {
    var id = DocumentId.random();
    jdbc.sql(
            "INSERT INTO document (id, sha256, mime_type, status)"
                + " VALUES (?, ?, 'application/pdf', ?)")
        .params(id.value(), shaChar.repeat(64), status)
        .update();
    return id;
  }

  private void extracted(DocumentId id, InvoiceExtraction extraction) {
    var now = Timestamp.from(Instant.now());
    jdbc.sql(
            "INSERT INTO stage_run (id, document_id, stage, run_no, status, started_at, ended_at)"
                + " VALUES (?, ?, 'EXTRACT', 1, 'SUCCEEDED', ?, ?)")
        .params(UUID.randomUUID(), id.value(), now, now)
        .update();
    jdbc.sql(
            "INSERT INTO extraction"
                + " (id, document_id, run_no, strategy, model_id, prompt_version, result)"
                + " VALUES (?, ?, 1, 'TEXT', 'example-local-8b', 'invoice-extraction/v1',"
                + " CAST(? AS JSONB))")
        .params(UUID.randomUUID(), id.value(), JSON.writeValueAsString(extraction))
        .update();
  }

  /** A synthetic, pseudonymized extraction; token fields hold made-up tokens. */
  private static InvoiceExtraction extraction(String description) {
    var line =
        new LineItem(
            Field.llm(1, 0.9),
            Field.llm(ChargeType.PRODUCT, 0.9),
            Field.llm(description, 0.8),
            null,
            Field.llm(new BigDecimal("1000.00"), 0.9),
            Field.llm(new BigDecimal("1"), 0.9),
            null,
            Field.llm(new BigDecimal("1000.00"), 0.9),
            List.of(
                new TaxLine(
                    Field.llm(TaxType.IGST, 0.9),
                    Field.llm(new BigDecimal("18"), 0.9),
                    Field.llm(new BigDecimal("180.00"), 0.9))),
            Field.llm(new BigDecimal("1180.00"), 0.9));
    return new InvoiceExtraction(
        Field.llm(DocumentClass.TAX_INVOICE, 0.9),
        Field.llm("tok_inv_0001", 0.9),
        Field.llm(LocalDate.of(2026, 1, 15), 0.9),
        Field.llm("tok_ord_0001", 0.9),
        null,
        Field.llm("Example Seller Pvt Ltd", 0.9),
        null,
        null,
        null,
        null,
        null,
        Field.llm("Karnataka", 0.9),
        null,
        Field.llm(Currency.INR, 0.9),
        Field.llm(new BigDecimal("1000.00"), 0.9),
        Field.llm(new BigDecimal("180.00"), 0.9),
        Field.llm(new BigDecimal("1180.00"), 0.9),
        null,
        null,
        List.of(line));
  }
}
