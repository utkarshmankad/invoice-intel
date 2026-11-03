package dev.invoiceintel.eval.run;

import java.time.Instant;

/**
 * The configuration of one eval run, written to {@code config.json} so the run is reproducible
 * (EVAL-4). Prompt versions and the git SHA join it with the report writers (M5.5).
 *
 * @param runId the run's folder name under the output folder
 * @param startedAt when the run started
 * @param dataset the dataset folder as given on the command line
 * @param strategy the extraction strategy name ({@code TEXT}, {@code VISION} or {@code HYBRID})
 * @param model the model ID
 * @param runner the {@link DocumentRunner#name()} that ran the documents
 */
public record RunConfig(
    String runId,
    Instant startedAt,
    String dataset,
    String strategy,
    String model,
    String runner) {}
