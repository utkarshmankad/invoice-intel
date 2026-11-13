package dev.invoiceintel.app;

import static dev.invoiceintel.app.TestAdminConfiguration.ADMIN_PASSWORD;
import static dev.invoiceintel.app.TestAdminConfiguration.ADMIN_USERNAME;
import static org.assertj.core.api.Assertions.assertThat;

import dev.invoiceintel.pii.PiiType;
import dev.invoiceintel.pii.TokenGenerator;
import dev.invoiceintel.reconcile.ImportId;
import dev.invoiceintel.reconcile.OrderHistoryStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Base64;
import java.util.UUID;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * Order-history import over HTTP (REC-1, architecture.md §6.7, §8) with a synthetic CSV and mapping
 * (testing.md). Order numbers are stored as the PII stage's tokens, never in clear.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({
  TestcontainersConfiguration.class,
  TestAdminConfiguration.class,
  TestPiiKeysConfiguration.class
})
class OrderHistoryImportIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String ORDER_A = "999-1111111-2222222";
  private static final String ORDER_B = "999-3333333-4444444";
  private static final String CSV =
      "Order ID,Order Date,Product Name,ASIN,Original Quantity,Unit Price,Total Amount,Shipping Address\r\n"
          + ORDER_A
          + ",2025-01-10T09:30:00Z,\"Synthetic Pen, blue\",B0SYNTH001,2,\"1,000.00\",2000.00,\"Fake Street 1\"\r\n"
          + ORDER_B
          + ",2025-02-01,Synthetic Mug,B0SYNTH002,1,499,499,Not Available\r\n"
          + ",2025-02-01,No order id,B0SYNTH003,1,1,1,x\r\n";

  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired TokenGenerator tokens;

  private final HttpClient http = HttpClient.newHttpClient();

  @DynamicPropertySource
  static void mappingFile(DynamicPropertyRegistry registry) throws URISyntaxException {
    var url = OrderHistoryImportIT.class.getResource("/fixtures/order-history/mapping.yml");
    var path = Path.of(java.util.Objects.requireNonNull(url, "mapping fixture").toURI()).toString();
    registry.add("invoice-intel.order-history.mapping-file", () -> path);
  }

  @Test
  void import_syntheticCsv_storesTokenizedRowsAndReportsCounts() throws Exception {
    var response = post(CSV);

    assertThat(response.statusCode()).isEqualTo(201);
    var body = JSON.readTree(response.body());
    assertThat(body.get("mapping").asString()).isEqualTo("synthetic-retail.csv");
    assertThat(body.get("kind").asString()).isEqualTo("RETAIL");
    assertThat(body.get("imported").asInt()).isEqualTo(2);
    assertThat(body.get("skipped").get("MISSING_ORDER_ID").asInt()).isEqualTo(1);
    assertThat(response.body()).doesNotContain(ORDER_A).doesNotContain("Synthetic Pen");

    var importId = new ImportId(UUID.fromString(body.get("importId").asString()));
    var items = new OrderHistoryStore(jdbc).find(importId);
    assertThat(items)
        .extracting(i -> i.orderToken())
        .containsExactlyInAnyOrder(
            tokens.token(PiiType.ORDER_NUMBER, ORDER_A),
            tokens.token(PiiType.ORDER_NUMBER, ORDER_B));
    var pen = items.stream().filter(i -> "B0SYNTH001".equals(i.asin())).findFirst().orElseThrow();
    assertThat(pen.orderDate()).isEqualTo(LocalDate.of(2025, 1, 10));
    assertThat(pen.productName()).isEqualTo("Synthetic Pen, blue");
    assertThat(pen.quantity()).isEqualTo(2);
    assertThat(pen.unitPrice()).isEqualByComparingTo("1000.00");
    assertThat(pen.itemTotal()).isEqualByComparingTo("2000.00");

    var clear =
        jdbc.sql("SELECT count(*) FROM order_history_item WHERE order_token IN (?, ?)")
            .params(ORDER_A, ORDER_B)
            .query(Long.class)
            .single();
    assertThat(clear).isZero();
  }

  @Test
  void import_unknownColumns_is400WithCodeAndStoresNothing() throws Exception {
    long before = count();
    var response = post("Order ID,Something Else\r\n" + ORDER_A + ",x\r\n");

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(JSON.readTree(response.body()).get("code").asString()).isEqualTo("UNKNOWN_COLUMNS");
    assertThat(response.body()).doesNotContain(ORDER_A);
    assertThat(count()).isEqualTo(before);
  }

  @Test
  void import_withoutLogin_is401() throws Exception {
    assertThat(http.send(request(CSV, false), HttpResponse.BodyHandlers.ofString()).statusCode())
        .isEqualTo(401);
  }

  private long count() {
    return jdbc.sql("SELECT count(*) FROM order_history_item").query(Long.class).single();
  }

  private HttpResponse<String> post(String csv) throws IOException, InterruptedException {
    return http.send(request(csv, true), HttpResponse.BodyHandlers.ofString());
  }

  private HttpRequest request(String csv, boolean authenticated) {
    String boundary = "boundary-" + UUID.randomUUID();
    var body = new ByteArrayOutputStream();
    body.writeBytes(
        ("--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"orders.csv\""
                + "\r\nContent-Type: text/csv\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII));
    body.writeBytes(csv.getBytes(StandardCharsets.UTF_8));
    body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
    var builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/order-history"))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
    if (authenticated) {
      var credentials = (ADMIN_USERNAME + ":" + ADMIN_PASSWORD).getBytes(StandardCharsets.UTF_8);
      builder.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials));
    }
    return builder.build();
  }
}
