package dev.invoiceintel.app;

import static dev.invoiceintel.app.TestAdminConfiguration.ADMIN_PASSWORD;
import static dev.invoiceintel.app.TestAdminConfiguration.ADMIN_USERNAME;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** G6, architecture.md §6.8: the review UI's layout, login page and navigation behind login. */
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
