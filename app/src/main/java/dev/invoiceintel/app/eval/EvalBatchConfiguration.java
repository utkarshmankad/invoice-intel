package dev.invoiceintel.app.eval;

import dev.invoiceintel.ingest.IngestService;
import dev.invoiceintel.pipeline.DocumentDetailsReader;
import dev.invoiceintel.pipeline.PipelineService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Runs the eval batch at start-up when {@code invoice-intel.eval-batch.input} is set, then closes
 * the application (architecture.md §6.9, ADR-24):
 *
 * <pre>
 * ./gradlew :app:bootRun --args='--spring.profiles.active=local
 *     --invoice-intel.eval-batch.input=data/raw --invoice-intel.eval-batch.output=data/eval-results'
 * </pre>
 */
@Configuration(proxyBeanMethods = false)
public class EvalBatchConfiguration {

  @Bean
  EvalBatch evalBatch(
      IngestService ingest,
      PipelineService pipeline,
      DocumentDetailsReader details,
      JdbcClient jdbc) {
    return new EvalBatch(ingest, pipeline, details, jdbc);
  }

  @Bean
  @ConditionalOnProperty(prefix = "invoice-intel.eval-batch", name = "input")
  ApplicationRunner evalBatchRunner(
      EvalBatch batch, EvalBatchProperties properties, ConfigurableApplicationContext context) {
    return _ -> {
      if (properties.output() == null) {
        throw new IllegalStateException("set invoice-intel.eval-batch.output");
      }
      try {
        batch.run(properties.input(), properties.output());
      } finally {
        context.close();
      }
    };
  }
}
