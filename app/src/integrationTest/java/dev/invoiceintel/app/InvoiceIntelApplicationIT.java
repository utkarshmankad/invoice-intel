package dev.invoiceintel.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({
  TestcontainersConfiguration.class,
  TestAdminConfiguration.class,
  TestPiiKeysConfiguration.class
})
class InvoiceIntelApplicationIT {

  @LocalServerPort int port;

  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void health_anonymousWithDatabase_isUp() throws IOException, InterruptedException {
    var response = get("/actuator/health");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"status\":\"UP\"").doesNotContain("components");
  }

  private HttpResponse<String> get(String path) throws IOException, InterruptedException {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
