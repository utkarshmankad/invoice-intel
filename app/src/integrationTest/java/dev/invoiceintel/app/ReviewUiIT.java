package dev.invoiceintel.app;

import static dev.invoiceintel.app.TestAdminConfiguration.ADMIN_PASSWORD;
import static dev.invoiceintel.app.TestAdminConfiguration.ADMIN_USERNAME;
import static dev.invoiceintel.app.TestcontainersConfiguration.RAW_BUCKET;
import static dev.invoiceintel.app.TestcontainersConfiguration.REDACTED_BUCKET;
import static org.assertj.core.api.Assertions.assertThat;

import dev.invoiceintel.common.DocumentClass;
import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.common.ExtractionId;
import dev.invoiceintel.common.extraction.BoundingBox;
import dev.invoiceintel.common.extraction.ChargeType;
import dev.invoiceintel.common.extraction.Currency;
import dev.invoiceintel.common.extraction.Evidence;
import dev.invoiceintel.common.extraction.Field;
import dev.invoiceintel.common.extraction.FieldSource;
import dev.invoiceintel.common.extraction.InvoiceExtraction;
import dev.invoiceintel.common.extraction.LineItem;
import dev.invoiceintel.common.extraction.TaxLine;
import dev.invoiceintel.common.extraction.TaxType;
import dev.invoiceintel.storage.BlobKey;
import dev.invoiceintel.storage.BlobStore;
import dev.invoiceintel.validate.Decision;
import dev.invoiceintel.validate.DecisionOutcome;
import dev.invoiceintel.validate.DecisionReason;
import dev.invoiceintel.validate.DecisionReason.Code;
import dev.invoiceintel.validate.DecisionStore;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

/**
 * G6, REV-1, REV-2, REV-3, architecture.md §6.8: the review UI's layout, login page, navigation
 * behind login, review queue page and document page with its masked page images and evidence
 * overlay, field corrections and approval.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({
  TestcontainersConfiguration.class,
  TestAdminConfiguration.class,
  TestPiiKeysConfiguration.class
})
class ReviewUiIT {

  private static final Pattern CSRF_INPUT =
      Pattern.compile(
          "name=\"_csrf\"[^>]*value=\"([^\"]+)\"|value=\"([^\"]+)\"[^>]*name=\"_csrf\"");

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired BlobStore blobStore;

  @Test
  void loginPage_anonymous_rendersUiLoginFormWithAssets() throws Exception {
    var page = send(newClient(), get("/login"));

    assertThat(page.statusCode()).isEqualTo(200);
    assertThat(page.headers().firstValue("Content-Type"))
        .hasValueSatisfying(type -> assertThat(type).startsWith("text/html"));
    assertThat(page.body())
        .contains(
            "<title>Log in · Invoice Intel</title>",
            "action=\"/login\"",
            "name=\"username\"",
            "name=\"password\"",
            "name=\"_csrf\"",
            "href=\"/css/app.css\"",
            // The webjar locator writes the version into the link.
            "src=\"/webjars/htmx.org/",
            "/dist/htmx.min.js\"",
            "name=\"csrf-token\"")
        // No navigation before login.
        .doesNotContain("Log out");
  }

  @Test
  void staticAssets_anonymous_areServedSoTheLoginPageRenders() throws Exception {
    var client = newClient();

    var css = send(client, get("/css/app.css"));
    var htmx = send(client, get("/webjars/htmx.org/dist/htmx.min.js"));
    var csrfScript = send(client, get("/js/htmx-csrf.js"));

    assertThat(css.statusCode()).isEqualTo(200);
    assertThat(htmx.statusCode()).isEqualTo(200);
    assertThat(htmx.body()).contains("htmx");
    assertThat(csrfScript.statusCode()).isEqualTo(200);
    assertThat(csrfScript.body()).contains("htmx:configRequest");
  }

  @Test
  void loginPage_afterFailedLogin_showsError() throws Exception {
    var client = newClient();
    send(client, loginPost(csrfToken(client), "not-the-password"));

    var page = send(client, get("/login?error"));

    assertThat(page.body()).contains("Wrong username or password.");
  }

  @Test
  void home_anonymous_redirectsToUiLoginPage() throws Exception {
    var response = send(newClient(), get("/"));

    assertThat(response.statusCode()).isEqualTo(302);
    assertThat(response.headers().firstValue("Location"))
        .hasValueSatisfying(location -> assertThat(location).endsWith("/login"));
  }

  @Test
  void home_afterLogin_rendersLayoutWithNavigation() throws Exception {
    var client = loggedInClient();

    var page = send(client, get("/"));

    assertThat(page.statusCode()).isEqualTo(200);
    assertThat(page.body())
        .contains(
            "<title>Home · Invoice Intel</title>",
            "class=\"nav\"",
            "href=\"/\"",
            "<span class=\"user\">" + ADMIN_USERNAME + "</span>",
            "action=\"/logout\"",
            "name=\"_csrf\"",
            "/dist/htmx.min.js\"");
  }

  @Test
  void logout_endsSessionAndShowsMessage() throws Exception {
    var client = loggedInClient();
    var home = send(client, get("/"));

    var logout = send(client, postForm("/logout", "_csrf=" + encode(csrfIn(home.body()))));

    assertThat(logout.statusCode()).isEqualTo(302);
    assertThat(logout.headers().firstValue("Location"))
        .hasValueSatisfying(location -> assertThat(location).endsWith("/login?logout"));
    assertThat(send(client, get("/login?logout")).body()).contains("You have been logged out.");
    assertThat(send(client, get("/")).statusCode()).isEqualTo(302);
  }

  @Test
  void reviewQueue_anonymous_redirectsToUiLoginPage() throws Exception {
    var response = send(newClient(), get("/review"));

    assertThat(response.statusCode()).isEqualTo(302);
    assertThat(response.headers().firstValue("Location"))
        .hasValueSatisfying(location -> assertThat(location).endsWith("/login"));
  }

  @Test
  void reviewQueue_afterLogin_listsNeedsReviewDocumentsWithReasons() throws Exception {
    jdbc.sql("DELETE FROM document").update();
    var queued = document("NEEDS_REVIEW", "a");
    decide(
        queued,
        new Decision(
            new BigDecimal("0.4100"),
            DecisionOutcome.NEEDS_REVIEW,
            List.of(
                new DecisionReason(Code.RULE_FAIL, "VAL-5", "tax total differs by 1.00"),
                new DecisionReason(Code.LOW_CONFIDENCE, null, "Score 0.4100 below 0.85"))));
    var approved = document("AUTO_APPROVED", "b");

    var page = send(loggedInClient(), get("/review"));

    assertThat(page.statusCode()).isEqualTo(200);
    assertThat(page.body())
        .contains(
            "<title>Review queue · Invoice Intel</title>",
            "href=\"/review\"",
            queued.value().toString(),
            "0.4100",
            "RULE_FAIL",
            "VAL-5",
            "tax total differs by 1.00",
            "LOW_CONFIDENCE",
            "1 document(s) waiting for review",
            // Default order: oldest first, so the Received header link flips to descending.
            "href=\"/review?sort=RECEIVED&amp;dir=DESC\"",
            "href=\"/review?sort=SCORE&amp;dir=ASC\"")
        .doesNotContain(approved.value().toString());
  }

  @Test
  void reviewQueue_sortedByScoreDescending_ordersRowsAndMarksHeader() throws Exception {
    jdbc.sql("DELETE FROM document").update();
    var low = document("NEEDS_REVIEW", "c");
    decide(low, review("0.3000"));
    var high = document("NEEDS_REVIEW", "d");
    decide(high, review("0.8000"));

    var page = send(loggedInClient(), get("/review?sort=SCORE&dir=DESC")).body();

    assertThat(page.indexOf(high.value().toString()))
        .isPositive()
        .isLessThan(page.indexOf(low.value().toString()));
    assertThat(page)
        .contains("aria-sort=\"descending\"", "href=\"/review?sort=SCORE&amp;dir=ASC\"");
  }

  @Test
  void reviewQueue_empty_saysNothingIsWaiting() throws Exception {
    jdbc.sql("DELETE FROM document").update();

    var page = send(loggedInClient(), get("/review"));

    assertThat(page.body()).contains("No documents are waiting for review.");
  }

  @Test
  void reviewQueue_unknownSort_isBadRequest() throws Exception {
    var response = send(loggedInClient(), get("/review?sort=AMOUNT"));

    assertThat(response.statusCode()).isEqualTo(400);
  }

  @Test
  void documentPage_afterLogin_overlaysEvidenceBoxesInTextLayerUnitsOnMaskedPage()
      throws Exception {
    var id = document("NEEDS_REVIEW", "e");
    textLayer(
        id,
        "[{\"pageNo\": 1, \"width\": 595.28, \"height\": 841.89,"
            + " \"source\": \"PDF_TEXT\", \"words\": []}]");
    redactedPage(id, 1);
    extracted(id, extraction(new Evidence(1, new BoundingBox(400, 700.5, 80.25, 12), List.of())));

    var page = send(loggedInClient(), get("/review/" + id.value()));

    assertThat(page.statusCode()).isEqualTo(200);
    assertThat(page.body())
        .contains(
            "<title>Document · Invoice Intel</title>",
            // The view box is the text layer page and the masked image fills it, so a box in
            // page units lands on the same spot of the image (REV-2: boxes align).
            "viewBox=\"0 0 595.28 841.89\"",
            "preserveAspectRatio=\"none\" x=\"0\" y=\"0\"",
            "href=\"/review/" + id.value() + "/pages/1\" width=\"595.28\" height=\"841.89\"",
            "x=\"400\" y=\"700.5\" width=\"80.25\" height=\"12\" data-field=\"grandTotal\"",
            "id=\"field-grandTotal\"",
            "value=\"1180.00\"",
            "value=\"TEST-INV-0001\"",
            "data-field=\"lineItems[0].description\"",
            "src=\"/js/evidence.js\"");
  }

  @Test
  void documentPage_boxOnAnotherPage_isDrawnOnlyThere() throws Exception {
    var id = document("NEEDS_REVIEW", "f");
    textLayer(
        id,
        "[{\"pageNo\": 1, \"width\": 2480, \"height\": 3508, \"source\": \"OCR\","
            + " \"words\": []}, {\"pageNo\": 2, \"width\": 2480, \"height\": 3508,"
            + " \"source\": \"OCR\", \"words\": []}]");
    redactedPage(id, 1);
    redactedPage(id, 2);
    extracted(id, extraction(new Evidence(2, new BoundingBox(100, 200, 300, 40), List.of())));

    var body = send(loggedInClient(), get("/review/" + id.value())).body();

    var page2 = body.indexOf("aria-label=\"Redacted page 2\"");
    var box = body.indexOf("x=\"100\" y=\"200\" width=\"300\" height=\"40\"");
    assertThat(body.indexOf("aria-label=\"Redacted page 1\"")).isPositive().isLessThan(page2);
    assertThat(box).isGreaterThan(page2);
  }

  @Test
  void documentPage_beforePiiAndExtraction_saysSoAndUnknownIsNotFound() throws Exception {
    var id = document("RECEIVED", "1");
    var client = loggedInClient();

    var page = send(client, get("/review/" + id.value()));

    assertThat(page.statusCode()).isEqualTo(200);
    assertThat(page.body()).contains("No redacted page images yet.", "No extraction yet.");
    assertThat(send(client, get("/review/" + UUID.randomUUID())).statusCode()).isEqualTo(404);
  }

  @Test
  void pageImage_servesMaskedPngOnly() throws Exception {
    var id = document("NEEDS_REVIEW", "2");
    byte[] png = redactedPage(id, 1);
    // A raw page render (PAGE_IMAGE) is never served (REV-2: no unredacted images).
    rawPage(id, 2);
    var client = loggedInClient();

    var image = client.send(get("/review/" + id.value() + "/pages/1"), BodyHandlers.ofByteArray());
    var raw = client.send(get("/review/" + id.value() + "/pages/2"), BodyHandlers.ofByteArray());
    var api =
        newClient()
            .send(
                HttpRequest.newBuilder(uri("/api/documents/" + id.value() + "/pages/1/redacted"))
                    .header("Authorization", basicAuth())
                    .build(),
                BodyHandlers.ofByteArray());
    var apiRaw =
        newClient()
            .send(
                HttpRequest.newBuilder(uri("/api/documents/" + id.value() + "/pages/2/redacted"))
                    .header("Authorization", basicAuth())
                    .build(),
                BodyHandlers.ofByteArray());

    assertThat(image.statusCode()).isEqualTo(200);
    assertThat(image.headers().firstValue("Content-Type")).hasValue("image/png");
    assertThat(image.headers().firstValue("Cache-Control")).hasValue("no-store");
    assertThat(image.body()).isEqualTo(png);
    assertThat(raw.statusCode()).isEqualTo(404);
    assertThat(api.statusCode()).isEqualTo(200);
    assertThat(api.body()).isEqualTo(png);
    assertThat(apiRaw.statusCode()).isEqualTo(404);
  }

  @Test
  void pageImage_anonymous_redirectsToLogin() throws Exception {
    var id = document("NEEDS_REVIEW", "3");
    redactedPage(id, 1);

    var response = send(newClient(), get("/review/" + id.value() + "/pages/1"));

    assertThat(response.statusCode()).isEqualTo(302);
  }

  @Test
  void documentPage_saveCorrections_storesAuditRowsDecidesAgainAndShowsHumanValue()
      throws Exception {
    var id = document("NEEDS_REVIEW", "4");
    extracted(id, extraction(null));
    var client = loggedInClient();
    var page = send(client, get("/review/" + id.value()));
    // Token fields are shown read-only and are not submitted (ADR-30).
    assertThat(page.body())
        .contains("name=\"field:grandTotal\"", "Save corrections", "Approve")
        .doesNotContain("name=\"field:invoiceNumber\"");

    var saved =
        send(
            client,
            postForm(
                "/review/" + id.value() + "/fields",
                "field%3AgrandTotal=1190.00&field%3AsellerName=Example+Seller+Pvt+Ltd&_csrf="
                    + encode(csrfIn(page.body()))));

    assertThat(saved.statusCode()).isEqualTo(302);
    assertThat(saved.headers().firstValue("Location"))
        .hasValueSatisfying(l -> assertThat(l).endsWith("/review/" + id.value()));
    var rows =
        jdbc.sql(
                "SELECT field_path, old_value, new_value, corrected_by FROM field_correction"
                    + " WHERE document_id = ?")
            .param(id.value())
            .query(
                (rs, n) ->
                    List.of(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)))
            .list();
    assertThat(rows).containsExactly(List.of("grandTotal", "1180.00", "1190.00", ADMIN_USERNAME));
    assertThat(
            jdbc.sql("SELECT count(*) FROM decision WHERE document_id = ?")
                .param(id.value())
                .query(Integer.class)
                .single())
        .isOne();
    var after = send(client, get("/review/" + id.value())).body();
    assertThat(after).contains("value=\"1190.00\"", "HUMAN");
  }

  @Test
  void documentPage_approve_movesDocumentToReviewedAndBackToQueue() throws Exception {
    var id = document("NEEDS_REVIEW", "5");
    extracted(id, extraction(null));
    var client = loggedInClient();
    var page = send(client, get("/review/" + id.value())).body();

    var approved =
        send(
            client,
            postForm("/review/" + id.value() + "/approve", "_csrf=" + encode(csrfIn(page))));

    assertThat(approved.statusCode()).isEqualTo(302);
    assertThat(approved.headers().firstValue("Location"))
        .hasValueSatisfying(l -> assertThat(l).endsWith("/review"));
    assertThat(status(id)).isEqualTo("REVIEWED");
    // A reviewed document's form is read-only.
    assertThat(send(client, get("/review/" + id.value())).body())
        .doesNotContain("Save corrections", "name=\"field:grandTotal\"");
  }

  @Test
  void documentPage_valueOfWrongType_storesNothingAndShowsError() throws Exception {
    var id = document("NEEDS_REVIEW", "6");
    extracted(id, extraction(null));
    var client = loggedInClient();
    var page = send(client, get("/review/" + id.value())).body();

    var saved =
        send(
            client,
            postForm(
                "/review/" + id.value() + "/fields",
                "field%3AgrandTotal=abc&_csrf=" + encode(csrfIn(page))));

    assertThat(saved.headers().firstValue("Location"))
        .hasValueSatisfying(l -> assertThat(l).endsWith("?error=invalid"));
    assertThat(corrections(id)).isZero();
    assertThat(send(client, get("/review/" + id.value() + "?error=invalid")).body())
        .contains("Correction not saved");
  }

  @Test
  void api_correctThenApprove_returnsCurrentValuesAndRejectsTokenFieldsAndSecondApproval()
      throws Exception {
    var id = document("NEEDS_REVIEW", "7");
    extracted(id, extraction(null));
    var client = newClient();

    var token = send(client, patch(id, "{\"fields\": {\"invoiceNumber\": \"X-1\"}}"));
    assertThat(token.statusCode()).isEqualTo(400);
    assertThat(corrections(id)).isZero();

    var unknown = send(client, patch(id, "{\"fields\": {\"noSuchField\": \"1\"}}"));
    assertThat(unknown.statusCode()).isEqualTo(400);

    var corrected =
        send(client, patch(id, "{\"fields\": {\"lineItems[0].taxLines[0].rate\": \"12\"}}"));
    assertThat(corrected.statusCode()).isEqualTo(200);
    var rate = JSON.readTree(corrected.body()).at("/extraction/lineItems/0/taxLines/0/rate");
    assertThat(rate.get("value").decimalValue()).isEqualByComparingTo("12");
    assertThat(rate.get("source").asString()).isEqualTo("HUMAN");

    var approve =
        HttpRequest.newBuilder(uri("/api/documents/" + id.value() + "/approve"))
            .header("Authorization", basicAuth())
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    assertThat(send(client, approve).statusCode()).isEqualTo(200);
    assertThat(status(id)).isEqualTo("REVIEWED");
    assertThat(send(client, approve).statusCode()).isEqualTo(409);
    assertThat(send(client, patch(id, "{\"fields\": {\"grandTotal\": \"1\"}}")).statusCode())
        .isEqualTo(409);
  }

  private HttpRequest patch(DocumentId id, String json) {
    return HttpRequest.newBuilder(uri("/api/documents/" + id.value() + "/fields"))
        .header("Authorization", basicAuth())
        .header("Content-Type", "application/json")
        .method("PATCH", HttpRequest.BodyPublishers.ofString(json))
        .build();
  }

  private String status(DocumentId id) {
    return jdbc.sql("SELECT status FROM document WHERE id = ?")
        .param(id.value())
        .query(String.class)
        .single();
  }

  private int corrections(DocumentId id) {
    return jdbc.sql("SELECT count(*) FROM field_correction WHERE document_id = ?")
        .param(id.value())
        .query(Integer.class)
        .single();
  }

  private void textLayer(DocumentId id, String pagesJson) {
    jdbc.sql(
            "INSERT INTO text_layer (id, document_id, run_no, layer)"
                + " VALUES (?, ?, 1, CAST(? AS JSONB))")
        .params(UUID.randomUUID(), id.value(), "{\"pages\": " + pagesJson + "}")
        .update();
  }

  private byte[] redactedPage(DocumentId id, int pageNo) throws IOException {
    var key = new BlobKey(REDACTED_BUCKET, "redacted/" + id.value() + "/pages/" + pageNo + ".png");
    return storePage(id, "REDACTED_PAGE", pageNo, key);
  }

  /** A raw page render's row; its blob isn't needed, the reader must not even look it up. */
  private void rawPage(DocumentId id, int pageNo) {
    jdbc.sql(
            "INSERT INTO blob_ref (id, document_id, kind, bucket, key, page_no)"
                + " VALUES (?, ?, 'PAGE_IMAGE', ?, ?, ?)")
        .params(
            UUID.randomUUID(),
            id.value(),
            RAW_BUCKET,
            "raw/" + id.value() + "/pages/" + pageNo + ".png",
            pageNo)
        .update();
  }

  private byte[] storePage(DocumentId id, String kind, int pageNo, BlobKey key) throws IOException {
    var image = new BufferedImage(20, 28, BufferedImage.TYPE_INT_RGB);
    var out = new ByteArrayOutputStream();
    ImageIO.write(image, "png", out);
    byte[] png = out.toByteArray();
    blobStore.put(key, png, "image/png");
    jdbc.sql(
            "INSERT INTO blob_ref (id, document_id, kind, bucket, key, page_no)"
                + " VALUES (?, ?, ?, ?, ?, ?)")
        .params(UUID.randomUUID(), id.value(), kind, key.bucket(), key.key(), pageNo)
        .update();
    return png;
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

  /** A synthetic extraction whose grand total carries {@code totalEvidence}. */
  private static InvoiceExtraction extraction(Evidence totalEvidence) {
    var line =
        new LineItem(
            Field.llm(1, 0.9),
            Field.llm(ChargeType.PRODUCT, 0.9),
            new Field<>("Example widget", 0.8, FieldSource.LLM, totalEvidence),
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
        Field.llm("TEST-INV-0001", 0.9),
        Field.llm(LocalDate.of(2026, 1, 15), 0.9),
        Field.llm("TEST-ORDER-0001", 0.9),
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
        new Field<>(new BigDecimal("1180.00"), 0.95, FieldSource.LLM, totalEvidence),
        null,
        null,
        List.of(line));
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

  private void decide(DocumentId id, Decision decision) {
    var extractionId = ExtractionId.random();
    jdbc.sql(
            "INSERT INTO extraction"
                + " (id, document_id, run_no, strategy, model_id, prompt_version, result)"
                + " VALUES (?, ?, 1, 'TEXT', 'example-local-8b', 'invoice-extraction/v1', '{}')")
        .params(extractionId.value(), id.value())
        .update();
    new DecisionStore(jdbc).replace(id, extractionId, decision);
  }

  private static Decision review(String score) {
    return new Decision(
        new BigDecimal(score),
        DecisionOutcome.NEEDS_REVIEW,
        List.of(new DecisionReason(Code.LOW_CONFIDENCE, null, "Score below 0.85")));
  }

  private HttpClient loggedInClient() throws IOException, InterruptedException {
    var client = newClient();
    var login = send(client, loginPost(csrfToken(client), ADMIN_PASSWORD));
    assertThat(login.headers().firstValue("Location"))
        .hasValueSatisfying(location -> assertThat(location).doesNotContain("error"));
    return client;
  }

  private String csrfToken(HttpClient client) throws IOException, InterruptedException {
    return csrfIn(send(client, get("/login")).body());
  }

  private static String csrfIn(String html) {
    var matcher = CSRF_INPUT.matcher(html);
    assertThat(matcher.find()).as("page has a CSRF field").isTrue();
    return matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
  }

  private HttpRequest loginPost(String csrfToken, String password) {
    return postForm(
        "/login",
        "username="
            + encode(ADMIN_USERNAME)
            + "&password="
            + encode(password)
            + "&_csrf="
            + encode(csrfToken));
  }

  private HttpRequest postForm(String path, String form) {
    return HttpRequest.newBuilder(uri(path))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(form))
        .build();
  }

  private HttpRequest get(String path) {
    return HttpRequest.newBuilder(uri(path)).build();
  }

  private URI uri(String path) {
    return URI.create("http://127.0.0.1:" + port + path);
  }

  private static HttpClient newClient() {
    return HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
  }

  private static HttpResponse<String> send(HttpClient client, HttpRequest request)
      throws IOException, InterruptedException {
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
