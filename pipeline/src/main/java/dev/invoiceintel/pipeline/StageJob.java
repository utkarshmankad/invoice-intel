package dev.invoiceintel.pipeline;

import java.util.Objects;
import java.util.UUID;
import org.jobrunr.jobs.lambdas.JobRequest;

/**
 * JobRunr job request for running one stage on one document. Holds IDs only, because JobRunr stores
 * it as JSON in its own tables and shows it on the dashboard (PII-8).
 */
public record StageJob(UUID documentId, Stage stage) implements JobRequest {

  public StageJob {
    Objects.requireNonNull(documentId, "documentId");
    Objects.requireNonNull(stage, "stage");
  }

  @Override
  public Class<StageJobHandler> getJobRequestHandler() {
    return StageJobHandler.class;
  }
}
