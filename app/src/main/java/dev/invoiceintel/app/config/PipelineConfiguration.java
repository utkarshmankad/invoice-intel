package dev.invoiceintel.app.config;

import dev.invoiceintel.pipeline.PipelineService;
import dev.invoiceintel.pipeline.StageHandler;
import dev.invoiceintel.pipeline.StageJobHandler;
import dev.invoiceintel.pipeline.TextStageHandler;
import dev.invoiceintel.storage.BlobStore;
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

  /** Text stage: text layer, page images and class hint (architecture.md §5.2, §6.2). */
  @Bean
  TextStageHandler textStage(
      BlobStore blobStore, JdbcClient jdbc, InvoiceIntelProperties properties) {
    var text = properties.text();
    return new TextStageHandler(
        blobStore,
        jdbc,
        new TextStageHandler.Settings(
            properties.storage().bucketRaw(),
            text.dpi(),
            text.ocrLanguages(),
            text.tessdataPath(),
            text.minTextChars(),
            text.maxConcurrentPageJobs()));
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
