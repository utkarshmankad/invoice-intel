package dev.invoiceintel.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.common.metrics.InvoiceMetrics;
import java.util.List;
import org.jobrunr.scheduling.JobRequestScheduler;
import org.jobrunr.storage.InMemoryStorageProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Argument checks that need no database; the state machine is tested in app's PipelineIT. */
class PipelineServiceTest {

  // Connects lazily, so no database is needed for checks that fail before any SQL.
  private final DriverManagerDataSource dataSource = new DriverManagerDataSource();

  @Test
  void constructor_twoHandlersForOneStage_isRejected() {
    var handlers =
        List.<StageHandler>of(
            new PlaceholderStageHandler(Stage.TEXT), new PlaceholderStageHandler(Stage.TEXT));

    assertThatThrownBy(() -> service(handlers))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("more than one handler for stage TEXT");
  }

  @Test
  void reprocess_stageWithoutHandler_isNotAllowed() {
    var service = service(List.of(new PlaceholderStageHandler(Stage.TEXT)));

    assertThatThrownBy(() -> service.reprocess(DocumentId.random(), Stage.PII))
        .isInstanceOf(ReprocessNotAllowedException.class)
        .hasMessage("stage PII is not available yet");
  }

  @Test
  void runStage_stageWithoutHandler_isAnError() {
    var service = service(List.of());

    assertThatThrownBy(() -> service.runStage(DocumentId.random(), Stage.TEXT))
        .isInstanceOf(IllegalStateException.class);
  }

  @ParameterizedTest
  @CsvSource({
    "EXTRACT, PII_LEAK, true",
    "VALIDATE, EXTRACTION_MISSING, true",
    "EXTRACT, UNEXPECTED_ERROR, false",
    "VALIDATE, UNEXPECTED_ERROR, false",
    "PII, PII_LEAK, false",
    "EXTRACT, EXTRACTION_MISSING, false"
  })
  void isFailedDecision_onlyTheSection66Failures_countAsFailedDecision(
      Stage stage, String code, boolean expected) {
    assertThat(PipelineService.isFailedDecision(stage, code)).isEqualTo(expected);
  }

  private PipelineService service(List<StageHandler> handlers) {
    return new PipelineService(
        JdbcClient.create(dataSource),
        new DataSourceTransactionManager(dataSource),
        new JobRequestScheduler(new InMemoryStorageProvider()),
        handlers,
        InvoiceMetrics.NONE);
  }
}
