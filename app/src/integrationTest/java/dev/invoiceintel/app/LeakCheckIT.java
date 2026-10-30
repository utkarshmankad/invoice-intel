package dev.invoiceintel.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.common.fixtures.InvoiceType;
import dev.invoiceintel.common.fixtures.SyntheticInvoice;
import dev.invoiceintel.common.fixtures.SyntheticInvoiceGenerator;
import dev.invoiceintel.ingest.IngestService;
import dev.invoiceintel.pii.LeakChecker;
import dev.invoiceintel.pii.PiiType;
import dev.invoiceintel.pipeline.PipelineService;
import dev.invoiceintel.pipeline.Stage;
import dev.invoiceintel.pipeline.StageFailedException;
import dev.invoiceintel.pipeline.StageHandler;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * The leak check (PII-6, architecture.md §6.3) against the vault entries the real Text and PII
 * stages store for a synthetic invoice. Until the Extract stage exists (M3.8), a stub Extract
 * handler returns a canned model response per document and fails the document with {@code PII_LEAK}
 * when the checker finds a known value in it, discarding the response.
 */
@SpringBootTest(
    properties = {
      "jobrunr.background-job-server.enabled=true",
      "jobrunr.background-job-server.poll-interval-in-seconds=5"
    })
@ActiveProfiles("test")
@Import({
  TestcontainersConfiguration.class,
  TestAdminConfiguration.class,
  TestPiiKeysConfiguration.class,
  LeakCheckIT.StubExtractConfiguration.class
})
class LeakCheckIT {

  private static final Duration TIMEOUT = Duration.ofSeconds(90);

  /** Canned model responses by document; hand-written, never from a real model (testing.md). */
  static final Map<DocumentId, String> RESPONSES = new ConcurrentHashMap<>();

  @Autowired IngestService ingest;
  @Autowired PipelineService pipeline;
  @Autowired LeakChecker leakChecker;
  @Autowired MeterRegistry meters;
  @Autowired JdbcClient jdbc;

  @Test
  void leakingResponse_failsDocumentWithPiiLeakAndCountsIt() {
    SyntheticInvoice invoice = SyntheticInvoiceGenerator.generate(InvoiceType.GST_TAX_INVOICE, 71);
    double before = leakCount();

    DocumentId id =
        run(invoice, "{\"buyer\": {\"name\": \"" + invoice.buyer().name().toUpperCase() + "\"}}");

    awaitStatus(id, "FAILED");
    assertThat(failure(id)).isEqualTo("EXTRACT " + LeakChecker.FAILURE_CODE);
    assertThat(leakCount()).isEqualTo(before + 1);
  }

  @Test
  void tokenOnlyResponse_passesTheCheck() {
    SyntheticInvoice invoice = SyntheticInvoiceGenerator.generate(InvoiceType.GST_TAX_INVOICE, 72);
    double before = leakCount();

    DocumentId id = run(invoice, "{\"buyer\": {\"name\": \"PERSON_AAAAAAAA\"}, \"total\": 10}");

    awaitStatus(id, "EXTRACTED");
    assertThat(leakCount()).isEqualTo(before);
  }

  @Test
  void check_readsOnlyTheDocumentsOwnVaultValues() {
    SyntheticInvoice first = SyntheticInvoiceGenerator.generate(InvoiceType.GST_TAX_INVOICE, 73);
    SyntheticInvoice second = SyntheticInvoiceGenerator.generate(InvoiceType.CREDIT_NOTE, 74);
    DocumentId firstId = run(first, "{}");
    DocumentId secondId = run(second, "{}");
    awaitStatus(firstId, "EXTRACTED");
    awaitStatus(secondId, "EXTRACTED");

    String secondName = second.buyer().name();
    assertThat(leakChecker.check(secondId, "name: " + secondName).leakedTypes())
        .contains(PiiType.PERSON);
    // The first document's vault values don't include the second buyer's name.
    assertThat(first.buyer().name()).isNotEqualTo(secondName);
    assertThat(leakChecker.check(firstId, "name: " + secondName).leaked()).isFalse();
  }

  private DocumentId run(SyntheticInvoice invoice, String response) {
    DocumentId id =
        ingest.ingest(List.of(SyntheticInvoiceGenerator.toPdf(invoice))).getFirst().documentId();
    RESPONSES.put(id, response);
    pipeline.start(id);
    return id;
  }

  private double leakCount() {
    var counter = meters.find("invoice.pii.leak").counter();
    return counter == null ? 0 : counter.count();
  }

  private void awaitStatus(DocumentId id, String expected) {
    await()
        .atMost(TIMEOUT)
        .until(
            () ->
                jdbc.sql("SELECT status FROM document WHERE id = ?")
                    .param(id.value())
                    .query(String.class)
                    .single()
                    .equals(expected));
  }

  private String failure(DocumentId id) {
    return jdbc.sql("SELECT failed_stage || ' ' || failure_code FROM document WHERE id = ?")
        .param(id.value())
        .query(String.class)
        .single();
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class StubExtractConfiguration {

    /** Stands in for the Extract stage of M3.8: a canned response, then the leak check. */
    @Bean
    StageHandler stubExtractStage(LeakChecker leakChecker) {
      return new StageHandler() {
        @Override
        public Stage stage() {
          return Stage.EXTRACT;
        }

        @Override
        public void run(DocumentId documentId, int runNo) {
          String response = RESPONSES.getOrDefault(documentId, "");
          if (leakChecker.check(documentId, response).leaked()) {
            throw new StageFailedException(LeakChecker.FAILURE_CODE);
          }
        }
      };
    }
  }
}
