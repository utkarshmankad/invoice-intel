package dev.invoiceintel.app;

import static dev.invoiceintel.app.TestAdminConfiguration.ADMIN_PASSWORD;
import static dev.invoiceintel.app.TestAdminConfiguration.ADMIN_USERNAME;
import static org.assertj.core.api.Assertions.assertThat;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.common.ExtractionId;
import dev.invoiceintel.validate.Decision;
import dev.invoiceintel.validate.DecisionOutcome;
import dev.invoiceintel.validate.DecisionReason;
import dev.invoiceintel.validate.DecisionReason.Code;
import dev.invoiceintel.validate.DecisionStore;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * G6, REV-1, architecture.md §6.8: the review UI's layout, login page, navigation behind login and
 * review queue page.
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

  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;

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
