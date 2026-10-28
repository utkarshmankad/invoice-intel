package dev.invoiceintel.pipeline;

import dev.invoiceintel.common.DocumentId;
import java.util.Objects;
import org.jobrunr.jobs.lambdas.JobRequestHandler;

/** Runs {@link StageJob}s; JobRunr looks it up as a Spring bean. */
public final class StageJobHandler implements JobRequestHandler<StageJob> {

  private final PipelineService pipeline;

  public StageJobHandler(PipelineService pipeline) {
    this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
  }

  @Override
  public void run(StageJob job) {
    pipeline.runStage(new DocumentId(job.documentId()), job.stage());
  }
}
