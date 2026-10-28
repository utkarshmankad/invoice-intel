package dev.invoiceintel.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.pipeline.PipelineService;
import dev.invoiceintel.pipeline.PlaceholderStageHandler;
import dev.invoiceintel.pipeline.ReprocessNotAllowedException;
import dev.invoiceintel.pipeline.Stage;
import dev.invoiceintel.pipeline.StageFailedException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * The stage chain on Postgres with a running JobRunr server (architecture.md §5.1, NFR-5). Text and
 * PII are placeholder stages, so a document should walk RECEIVED → TEXT_READY → PII_CLEAN.
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
  PipelineIT.PiiPlaceholder.class
})
class PipelineIT {

  private static final Duration TIMEOUT = Duration.ofSeconds(60);

  @Autowired PipelineService pipeline;
  @Autowired JdbcClient jdbc;

  @MockitoSpyBean(name = "textStage")
  PlaceholderStageHandler textStage;

  @Test
  void start_placeholderStages_advanceDocumentThroughChain() {
    DocumentId id = receivedDocument();

    pipeline.start(id);

    awaitStatus(id, "PII_CLEAN");
    assertThat(runs(id)).containsExactly("PII 1 SUCCEEDED null", "TEXT 1 SUCCEEDED null");
  }

  @Test
  void reprocess_fromText_supersedesEarlierRunsAndRunsAgain() {
    DocumentId id = receivedDocument();
    pipeline.start(id);
    awaitStatus(id, "PII_CLEAN");

    assertThat(pipeline.reprocess(id, Stage.TEXT)).isTrue();

    await().atMost(TIMEOUT).until(() -> runs(id).contains("PII 2 SUCCEEDED null"));
    assertThat(runs(id))
        .containsExactly(
            "PII 1 SUPERSEDED null",
            "PII 2 SUCCEEDED null",
            "TEXT 1 SUPERSEDED null",
            "TEXT 2 SUCCEEDED null");
    assertThat(status(id)).isEqualTo("PII_CLEAN");
  }

  @Test
  void reprocess_fromPii_keepsTextRun() {
    DocumentId id = receivedDocument();
    pipeline.start(id);
    awaitStatus(id, "PII_CLEAN");

    pipeline.reprocess(id, Stage.PII);

    await().atMost(TIMEOUT).until(() -> runs(id).contains("PII 2 SUCCEEDED null"));
    assertThat(runs(id))
        .containsExactly("PII 1 SUPERSEDED null", "PII 2 SUCCEEDED null", "TEXT 1 SUCCEEDED null");
  }

  @Test
  void stageFailure_marksDocumentFailedWithStageAndCode() {
    DocumentId id = receivedDocument();
    doThrow(new StageFailedException("TEST_FAILURE")).when(textStage).run(eq(id), anyInt());

    pipeline.start(id);

    awaitStatus(id, "FAILED");
    assertThat(failure(id)).isEqualTo("TEXT TEST_FAILURE");
    assertThat(runs(id)).containsExactly("TEXT 1 FAILED TEST_FAILURE");
  }

  @Test
  void unexpectedException_failsWithGenericCode() {
    DocumentId id = receivedDocument();
    doThrow(new IllegalStateException("boom")).when(textStage).run(eq(id), anyInt());

    pipeline.start(id);

    awaitStatus(id, "FAILED");
    assertThat(failure(id)).isEqualTo("TEXT " + PipelineService.UNEXPECTED_ERROR);
  }

  @Test
  void reprocess_failedDocument_clearsFailureAndRetries() {
    DocumentId id = receivedDocument();
    doThrow(new StageFailedException("TEST_FAILURE")).when(textStage).run(eq(id), anyInt());
    pipeline.start(id);
    awaitStatus(id, "FAILED");

    // The failed document reached RECEIVED only, so PII can't be re-run yet.
    assertThatThrownBy(() -> pipeline.reprocess(id, Stage.PII))
        .isInstanceOf(ReprocessNotAllowedException.class);

    reset(textStage);
    assertThat(pipeline.reprocess(id, Stage.TEXT)).isTrue();

    awaitStatus(id, "PII_CLEAN");
    assertThat(failure(id)).isEqualTo("null null");
    assertThat(runs(id))
        .containsExactly(
            "PII 1 SUCCEEDED null", "TEXT 1 SUPERSEDED TEST_FAILURE", "TEXT 2 SUCCEEDED null");
  }

  @Test
  void reprocess_unknownDocument_returnsFalse() {
    assertThat(pipeline.reprocess(DocumentId.random(), Stage.TEXT)).isFalse();
  }

  @Test
  void stageJob_documentNotInInputState_isSkipped() {
    DocumentId id = receivedDocument();
    jdbc.sql("UPDATE document SET status = 'TEXT_READY' WHERE id = ?").param(id.value()).update();

    // Text expects RECEIVED; Text is skipped and nothing else is enqueued.
    pipeline.start(id);
    DocumentId marker = receivedDocument();
    pipeline.start(marker);
    awaitStatus(marker, "PII_CLEAN");

    assertThat(runs(id)).isEmpty();
    assertThat(status(id)).isEqualTo("TEXT_READY");
  }

  private DocumentId receivedDocument() {
    DocumentId id = DocumentId.random();
    byte[] hash = new byte[32];
    ThreadLocalRandom.current().nextBytes(hash);
    jdbc.sql("INSERT INTO document (id, sha256, mime_type, status) VALUES (?, ?, ?, 'RECEIVED')")
        .params(id.value(), HexFormat.of().formatHex(hash), "application/pdf")
        .update();
    return id;
  }

  private void awaitStatus(DocumentId id, String expected) {
    await().atMost(TIMEOUT).until(() -> status(id).equals(expected));
  }

  private String status(DocumentId id) {
    return jdbc.sql("SELECT status FROM document WHERE id = ?")
        .param(id.value())
        .query(String.class)
        .single();
  }

  private String failure(DocumentId id) {
    return jdbc.sql("SELECT failed_stage || ' ' || failure_code FROM document WHERE id = ?")
        .param(id.value())
        .query((rs, row) -> rs.getString(1) == null ? "null null" : rs.getString(1))
        .single();
  }

  private List<String> runs(DocumentId id) {
    return jdbc.sql(
            """
            SELECT stage || ' ' || run_no || ' ' || status || ' ' || COALESCE(error_code, 'null')
            FROM stage_run WHERE document_id = ? ORDER BY stage, run_no
            """)
        .param(id.value())
        .query(String.class)
        .list();
  }

  /** A second placeholder stage, so the chain and reprocess-from-a-later-stage are covered. */
  @TestConfiguration(proxyBeanMethods = false)
  static class PiiPlaceholder {

    @Bean
    PlaceholderStageHandler piiStage() {
      return new PlaceholderStageHandler(Stage.PII);
    }
  }
}
