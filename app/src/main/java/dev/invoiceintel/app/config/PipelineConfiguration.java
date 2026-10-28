package dev.invoiceintel.app.config;

import dev.invoiceintel.pipeline.PipelineService;
import dev.invoiceintel.pipeline.PlaceholderStageHandler;
import dev.invoiceintel.pipeline.Stage;
import dev.invoiceintel.pipeline.StageHandler;
import dev.invoiceintel.pipeline.StageJobHandler;
import java.util.List;
import org.jobrunr.scheduling.JobRequestScheduler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Pipeline wiring (architecture.md §5): every {@link StageHandler} bean joins the stage chain, and
 * JobRunr (configured under {@code jobrunr.*}) runs the stage jobs.
 */
@Configuration(proxyBeanMethods = false)
public class PipelineConfiguration {

  /** No-op Text stage until the Text job lands (plan M1.10). */
  @Bean
  PlaceholderStageHandler textStage() {
    return new PlaceholderStageHandler(Stage.TEXT);
  }

  @Bean
  PipelineService pipelineService(
      JdbcClient jdbc,
      PlatformTransactionManager transactionManager,
      JobRequestScheduler jobs,
      List<StageHandler> handlers) {
    return new PipelineService(jdbc, transactionManager, jobs, handlers);
  }

  @Bean
  StageJobHandler stageJobHandler(PipelineService pipeline) {
    return new StageJobHandler(pipeline);
  }
}
