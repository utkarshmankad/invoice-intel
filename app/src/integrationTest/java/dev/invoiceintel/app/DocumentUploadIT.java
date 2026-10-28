package dev.invoiceintel.app;

import static dev.invoiceintel.app.TestAdminConfiguration.ADMIN_PASSWORD;
import static dev.invoiceintel.app.TestAdminConfiguration.ADMIN_USERNAME;
import static dev.invoiceintel.app.TestcontainersConfiguration.RAW_BUCKET;
import static org.assertj.core.api.Assertions.assertThat;

import dev.invoiceintel.common.fixtures.InvoiceType;
import dev.invoiceintel.common.fixtures.SyntheticInvoiceGenerator;
import dev.invoiceintel.storage.BlobKey;
import dev.invoiceintel.storage.BlobStore;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
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
import tools.jackson.databind.ObjectMapper;

/** {@code POST /api/documents}: ING-1..4, architecture.md §6.1. Synthetic fixtures only. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestAdminConfiguration.class})
class DocumentUploadIT {

  /** Seed of the committed smoke fixture; changing it means regenerating the file. */
  private static final long SMOKE_SEED = 20261028L;

  private static final Path SMOKE_FIXTURE =
      Path.of("src/integrationTest/resources/fixtures/smoke/gst-tax-invoice.pdf");

  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired BlobStore blobStore;
  @Autowired ObjectMapper json;

  private final HttpClient http = HttpClient.newHttpClient();

  @BeforeEach
  void clean() {
    jdbc.sql("DELETE FROM document").update();
  }

  @Test
  void upload_newPdf_storesOriginalAndCreatesReceivedDocument() throws Exception {
    byte[] pdf = invoicePdf(1);

    var response = upload(List.of(new Part("invoice.pdf", "application/pdf", pdf)));

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = json.readTree(response.body());
    assertThat(body).hasSize(1);
    UUID id = UUID.fromString(body.get(0).get("id").asString());
    assertThat(body.get(0).get("duplicate").asBoolean()).isFalse();

    assertThat(
            jdbc.sql("SELECT status FROM document WHERE id = ?")
                .param(id)
                .query(String.class)
                .single())
        .isEqualTo("RECEIVED");
    String key = "raw/" + id + "/original.pdf";
    assertThat(
            jdbc.sql("SELECT bucket || '/' || key FROM blob_ref WHERE document_id = ? AND kind = ?")
                .params(id, "ORIGINAL")
                .query(String.class)
                .single())
        .isEqualTo(RAW_BUCKET + "/" + key);
    assertThat(blobStore.get(new BlobKey(RAW_BUCKET, key)))
        .hasValueSatisfying(stored -> assertThat(stored).isEqualTo(pdf));
  }

  @Test
  void upload_sameBytesTwice_returnsExistingDocument() throws Exception {
    byte[] pdf = invoicePdf(2);

    UUID first = firstId(upload(List.of(new Part("a.pdf", "application/pdf", pdf))));
    var second = upload(List.of(new Part("renamed.pdf", "application/pdf", pdf)));

    JsonNode body = json.readTree(second.body());
    assertThat(UUID.fromString(body.get(0).get("id").asString())).isEqualTo(first);
    assertThat(body.get(0).get("duplicate").asBoolean()).isTrue();
    assertThat(jdbc.sql("SELECT count(*) FROM document").query(Long.class).single()).isOne();
  }

  @Test
  void upload_batchOfPdfAndPng_createsOneDocumentEach() throws Exception {
    var response =
        upload(
            List.of(
                new Part("a.pdf", "application/pdf", invoicePdf(3)),
                new Part("photo.png", "image/png", png())));

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(json.readTree(response.body())).hasSize(2);
    assertThat(
            jdbc.sql("SELECT mime_type FROM document ORDER BY mime_type")
                .query(String.class)
                .list())
        .containsExactly("application/pdf", "image/png");
  }

  @Test
  void upload_textDisguisedAsPdf_isRejectedWith415AndStoresNothing() throws Exception {
    byte[] text = "plain text with a .pdf name".getBytes(StandardCharsets.UTF_8);

    var response =
        upload(
            List.of(
                new Part("good.pdf", "application/pdf", invoicePdf(4)),
                new Part("fake.pdf", "application/pdf", text)));

    assertThat(response.statusCode()).isEqualTo(415);
    assertThat(response.headers().firstValue("Content-Type"))
        .hasValueSatisfying(type -> assertThat(type).startsWith("application/problem+json"));
    JsonNode problem = json.readTree(response.body());
    assertThat(problem.get("fileIndex").asInt()).isEqualTo(1);
    assertThat(problem.get("detectedType").asString()).isEqualTo("text/plain");
    // The whole batch is rejected before anything is stored.
    assertThat(jdbc.sql("SELECT count(*) FROM document").query(Long.class).single()).isZero();
  }

  @Test
  void upload_anonymous_isRejected() throws Exception {
    var request = multipart(List.of(new Part("a.pdf", "application/pdf", invoicePdf(5))), false);

    assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
        .isEqualTo(401);
  }

  @Test
  void get_uploadedDocument_returnsReceivedStatus() throws Exception {
    UUID id = firstId(upload(List.of(new Part("a.pdf", "application/pdf", invoicePdf(6)))));

    var response = get("/api/documents/" + id);

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = json.readTree(response.body());
    assertThat(body.get("id").asString()).isEqualTo(id.toString());
    assertThat(body.get("status").asString()).isEqualTo("RECEIVED");
  }

  @Test
  void get_unknownDocument_is404ProblemDetail() throws Exception {
    var response = get("/api/documents/" + UUID.randomUUID());

    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(response.headers().firstValue("Content-Type"))
        .hasValueSatisfying(type -> assertThat(type).startsWith("application/problem+json"));
  }

  @Test
  void get_malformedId_is400() throws Exception {
    assertThat(get("/api/documents/not-a-uuid").statusCode()).isEqualTo(400);
  }

  @Test
  void smokeFixture_isTheGeneratedGstTaxInvoice() throws IOException {
    // The CI smoke test uploads this file (scripts/ci/smoke-test.sh); keep it in sync with the
    // generator so it stays synthetic.
    byte[] committed = Files.readAllBytes(SMOKE_FIXTURE);

    assertThat(committed).isEqualTo(invoicePdf(SMOKE_SEED));
  }

  private static byte[] invoicePdf(long seed) {
    return SyntheticInvoiceGenerator.toPdf(
        SyntheticInvoiceGenerator.generate(InvoiceType.GST_TAX_INVOICE, seed));
  }

  private static byte[] png() throws IOException {
    var out = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", out);
    return out.toByteArray();
  }

  private UUID firstId(HttpResponse<String> response) {
    return UUID.fromString(json.readTree(response.body()).get(0).get("id").asString());
  }

  private HttpResponse<String> get(String path) throws IOException, InterruptedException {
    var credentials = (ADMIN_USERNAME + ":" + ADMIN_PASSWORD).getBytes(StandardCharsets.UTF_8);
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials))
            .build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> upload(List<Part> parts) throws IOException, InterruptedException {
    return http.send(multipart(parts, true), HttpResponse.BodyHandlers.ofString());
  }

  private HttpRequest multipart(List<Part> parts, boolean authenticated) {
    String boundary = "boundary-" + UUID.randomUUID();
    var body = new ByteArrayOutputStream();
    for (Part part : parts) {
      body.writeBytes(
          ("--"
                  + boundary
                  + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\""
                  + part.filename()
                  + "\"\r\nContent-Type: "
                  + part.contentType()
                  + "\r\n\r\n")
              .getBytes(StandardCharsets.US_ASCII));
      body.writeBytes(part.content());
      body.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
    }
    body.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
    var builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/documents"))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
    if (authenticated) {
      var credentials = (ADMIN_USERNAME + ":" + ADMIN_PASSWORD).getBytes(StandardCharsets.UTF_8);
      builder.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials));
    }
    return builder.build();
  }

  private record Part(String filename, String contentType, byte[] content) {}
}
