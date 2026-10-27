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
import java.util.Base64;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** NFR-2, architecture.md §11: Basic auth for the API, form login for everything else. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestAdminConfiguration.class})
class SecurityIT {

  private static final Pattern CSRF_INPUT =
      Pattern.compile(
          "name=\"_csrf\"[^>]*value=\"([^\"]+)\"|value=\"([^\"]+)\"[^>]*name=\"_csrf\"");

  @LocalServerPort int port;

  @Test
  void api_anonymous_gets401WithoutBrowserChallenge() throws Exception {
    var response = send(newClient(), get("/api/documents"));

    assertThat(response.statusCode()).isEqualTo(401);
    // No challenge, so browsers never prompt for and cache Basic credentials (ADR-12).
    assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
  }

  @Test
  void api_wrongPassword_gets401() throws Exception {
    var response = send(newClient(), basic(get("/api/documents"), "not-the-password"));

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
  }

  @Test
  void api_adminCredentials_passAuthenticationWithoutSession() throws Exception {
    var response = send(newClient(), basic(get("/api/documents"), ADMIN_PASSWORD));

    // No API endpoints exist yet; anything but 401/403 means authentication passed.
    assertThat(response.statusCode()).isNotIn(401, 403);
    assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
  }

  @Test
  void ui_anonymous_redirectsToLogin() throws Exception {
    var response = send(newClient(), get("/"));

    assertThat(response.statusCode()).isEqualTo(302);
    assertThat(response.headers().firstValue("Location"))
        .hasValueSatisfying(location -> assertThat(location).endsWith("/login"));
  }

  @Test
  void actuator_anonymousNonHealthEndpoint_redirectsToLogin() throws Exception {
    assertThat(send(newClient(), get("/actuator/env")).statusCode()).isEqualTo(302);
  }

  @Test
  void formLogin_validCredentials_opensSessionAndKeepsActuatorClosed() throws Exception {
    var client = newClient();

    var login = send(client, loginPost(csrfToken(client), ADMIN_PASSWORD));

    assertThat(login.statusCode()).isEqualTo(302);
    assertThat(login.headers().firstValue("Location"))
        .hasValueSatisfying(location -> assertThat(location).doesNotContain("error"));
    // Logged in, but only health is exposed over HTTP.
    assertThat(send(client, get("/actuator/env")).statusCode()).isEqualTo(404);
  }

  @Test
  void formLogin_wrongPassword_redirectsToLoginError() throws Exception {
    var client = newClient();

    var login = send(client, loginPost(csrfToken(client), "not-the-password"));

    assertThat(login.headers().firstValue("Location"))
        .hasValueSatisfying(location -> assertThat(location).endsWith("/login?error"));
  }

  @Test
  void formLogin_withoutCsrfToken_isForbidden() throws Exception {
    var response = send(newClient(), loginPost(null, ADMIN_PASSWORD));

    assertThat(response.statusCode()).isEqualTo(403);
  }

  private String csrfToken(HttpClient client) throws IOException, InterruptedException {
    var page = send(client, get("/login"));
    assertThat(page.statusCode()).isEqualTo(200);
    var matcher = CSRF_INPUT.matcher(page.body());
    assertThat(matcher.find()).as("login page has a CSRF field").isTrue();
    return matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
  }

  private HttpRequest loginPost(String csrfToken, String password) {
    var form =
        "username="
            + encode(ADMIN_USERNAME)
            + "&password="
            + encode(password)
            + (csrfToken == null ? "" : "&_csrf=" + encode(csrfToken));
    return HttpRequest.newBuilder(uri("/login"))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(form))
        .build();
  }

  private HttpRequest get(String path) {
    return HttpRequest.newBuilder(uri(path)).build();
  }

  private static HttpRequest basic(HttpRequest request, String password) {
    var credentials = (ADMIN_USERNAME + ":" + password).getBytes(StandardCharsets.UTF_8);
    return HttpRequest.newBuilder(request, (name, value) -> true)
        .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials))
        .build();
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
