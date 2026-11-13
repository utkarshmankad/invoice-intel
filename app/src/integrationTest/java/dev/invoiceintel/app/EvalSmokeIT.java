package dev.invoiceintel.app;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.invoiceintel.app.eval.EvalBatch;
import dev.invoiceintel.common.fixtures.InvoiceType;
import dev.invoiceintel.common.fixtures.LineItem;
import dev.invoiceintel.common.fixtures.SyntheticInvoice;
import dev.invoiceintel.common.fixtures.SyntheticInvoiceGenerator;
import dev.invoiceintel.eval.cli.EvalCli;
import dev.invoiceintel.eval.cli.GitSha;
import dev.invoiceintel.eval.run.StubDocumentRunner;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Eval smoke (NFR-9, architecture.md §13, M5.9): synthetic invoices with label files go through the
 * whole eval path the owner runs on real data (§6.9, ADR-24): the in-process eval batch runs them
 * through the real Text, PII, Extract and Validate stages with a stubbed model, then {@code eval
 * run --results} scores the result files and writes the run folder, and {@code eval compare} reads
 * it back. It checks wiring, not accuracy: the stub answers the same extraction for every document.
 */
@SpringBootTest(properties = "invoice-intel.llm.extraction.model=local")
@ActiveProfiles("test")
@Import({
  TestcontainersConfiguration.class,
  TestAdminConfiguration.class,
  TestPiiKeysConfiguration.class
})
class EvalSmokeIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final WireMockServer WIREMOCK = new WireMockServer(wireMockConfig().dynamicPort());

  /** One of each generator type plus extra tax invoices: five originals (§13). */
  private static final List<Map.Entry<InvoiceType, Long>> ORIGINALS =
      List.of(
          Map.entry(InvoiceType.GST_TAX_INVOICE, 5911L),
          Map.entry(InvoiceType.GST_TAX_INVOICE, 5912L),
          Map.entry(InvoiceType.GST_TAX_INVOICE, 5913L),
          Map.entry(InvoiceType.CREDIT_NOTE, 5914L),
          Map.entry(InvoiceType.PRE_GST_INVOICE, 5915L));

  /** Labelled but never given to the batch, so its result is missing (SKIPPED). */
  private static final long UNRUN_SEED = 5916L;

  @Autowired EvalBatch batch;

  @TempDir Path originals;
  @TempDir Path labels;
  @TempDir Path results;
  @TempDir Path runs;

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

  @Test
  void evalPath_syntheticDataset_batchThenRunThenCompareWriteReports() throws IOException {
    stubModel(extractionJson());
    List<String> labelledValues = new ArrayList<>();
    for (Map.Entry<InvoiceType, Long> original : ORIGINALS) {
      SyntheticInvoice invoice =
          SyntheticInvoiceGenerator.generate(original.getKey(), original.getValue());
      byte[] pdf = SyntheticInvoiceGenerator.toPdf(invoice);
      Files.write(originals.resolve(original.getValue() + ".pdf"), pdf);
      writeLabel(sha256(pdf), invoice);
      labelledValues.add(invoice.invoiceNumber());
    }
    SyntheticInvoice unrun =
        SyntheticInvoiceGenerator.generate(InvoiceType.GST_TAX_INVOICE, UNRUN_SEED);
    writeLabel(sha256(SyntheticInvoiceGenerator.toPdf(unrun)), unrun);

    EvalBatch.Summary summary = batch.run(originals, results);

    assertThat(summary).isEqualTo(new EvalBatch.Summary(ORIGINALS.size(), 0));

    StringWriter out = new StringWriter();
    StringWriter err = new StringWriter();
    EvalCli cli =
        new EvalCli(
            (strategy, model) -> new StubDocumentRunner(),
            () -> GitSha.UNKNOWN,
            Clock.systemUTC(),
            new PrintWriter(out, true),
            new PrintWriter(err, true));

    int code =
        cli.execute(
            "run",
            "--dataset",
            labels.toString(),
            "--strategy",
            "TEXT",
            "--model",
            "local",
            "--out",
            runs.toString(),
            "--run-id",
            "smoke",
            "--usd-inr",
            "84.00",
            "--results",
            results.toString());

    assertThat(code).as(err.toString()).isZero();
    assertThat(out.toString())
        .contains("Run smoke: 6 documents (5 extracted, 1 skipped, 0 failed)");
    Path run = runs.resolve("smoke");
    assertThat(run.resolve("config.json")).exists();
    assertThat(Files.readAllLines(run.resolve("documents.csv"))).hasSize(1 + 6);
    assertThat(Files.readAllLines(run.resolve("fields.csv")))
        .anyMatch(l -> l.startsWith("grandTotal,"));
    String report = Files.readString(run.resolve("report.md"));
    assertThat(report)
        .contains(
            "| Header field accuracy |",
            "| Line-item F1 |",
            "| Straight-through rate |",
            "| False auto-approval rate |",
            "| Cost per document (INR) |",
            "| Latency p95 |");
    // Reports name documents by SHA-256 only, never a labelled value (PII-8).
    String everything =
        report
            + Files.readString(run.resolve("documents.csv"))
            + Files.readString(run.resolve("fields.csv"))
            + Files.readString(run.resolve("config.json"));
    assertThat(everything).doesNotContain(labelledValues);

    StringWriter compared = new StringWriter();
    EvalCli compareCli =
        new EvalCli(
            (strategy, model) -> new StubDocumentRunner(),
            () -> GitSha.UNKNOWN,
            Clock.systemUTC(),
            new PrintWriter(compared, true),
            new PrintWriter(err, true));
    assertThat(compareCli.execute("compare", run.toString(), run.toString())).isZero();
    assertThat(compared.toString()).contains("| Documents | 6 | 6 |", "| Extracted | 5 | 5 |");
  }

  /**
   * A label (architecture.md §9.2) from the generator's ground truth: the header fields and line
   * items it knows, the rest null, no PII spans (the PII metric isn't in the report, ADR-24).
   */
  private void writeLabel(String sha256, SyntheticInvoice invoice) throws IOException {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("documentClass", documentClass(invoice.type()));
    fields.put("invoiceNumber", invoice.invoiceNumber());
    fields.put("invoiceDate", invoice.invoiceDate().toString());
    fields.put("orderNumber", invoice.orderNumber());
    fields.put("orderDate", invoice.orderDate() == null ? null : invoice.orderDate().toString());
    fields.put("sellerName", invoice.seller().name());
    for (String unknown :
        List.of(
            "sellerGstin",
            "sellerPan",
            "sellerAddress",
            "placeOfSupply",
            "placeOfDelivery",
            "buyerState",
            "reverseCharge",
            "amountInWords")) {
      fields.put(unknown, null);
    }
    fields.put("currency", "INR");
    fields.put("subtotal", amount(invoice.netTotal()));
    fields.put("totalTax", amount(invoice.taxTotal()));
    fields.put("grandTotal", amount(invoice.grandTotal()));
    fields.put("originalInvoiceRef", invoice.creditedInvoiceNumber().orElse(null));
    List<Map<String, Object>> items = new ArrayList<>();
    int lineNo = 1;
    for (LineItem item : invoice.lineItems()) {
      Map<String, Object> line = new LinkedHashMap<>();
      line.put("lineNo", lineNo++);
      line.put("chargeType", "PRODUCT");
      line.put("description", item.description());
      line.put("hsnSac", item.hsnCode());
      line.put("unitPrice", amount(item.unitPrice()));
      line.put("quantity", Integer.toString(item.quantity()));
      line.put("discount", null);
      line.put("netAmount", amount(item.netAmount()));
      line.put("taxLines", List.of());
      line.put("lineTotal", amount(item.totalAmount()));
      items.add(line);
    }
    fields.put("lineItems", items);
    Map<String, Object> label = new LinkedHashMap<>();
    label.put("version", 1);
    label.put("sha256", sha256);
    label.put("synthetic", true);
    label.put("fields", fields);
    label.put("piiSpans", List.of());
    Files.writeString(labels.resolve(sha256 + ".json"), JSON.writeValueAsString(label));
  }

  private static String documentClass(InvoiceType type) {
    return switch (type) {
      case GST_TAX_INVOICE -> "TAX_INVOICE";
      case CREDIT_NOTE -> "CREDIT_NOTE";
      case PRE_GST_INVOICE -> "PRE_GST_INVOICE";
    };
  }

  private static String amount(BigDecimal value) {
    return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String extractionJson() throws IOException {
    try (InputStream in =
        EvalSmokeIT.class.getResourceAsStream("/fixtures/llm/invoice-extraction-response.json")) {
      if (in == null) {
        throw new IOException("fixture missing");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
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
}
