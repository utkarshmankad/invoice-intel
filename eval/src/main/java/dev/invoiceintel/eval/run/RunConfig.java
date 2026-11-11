package dev.invoiceintel.eval.run;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The configuration of one eval run, written to {@code config.json} so the run is reproducible
 * (EVAL-4).
 *
 * @param runId the run's folder name under the output folder
 * @param startedAt when the run started
 * @param dataset the dataset folder as given on the command line
 * @param strategy the extraction strategy name ({@code TEXT}, {@code VISION} or {@code HYBRID})
 * @param model the model ID asked for on the command line
 * @param runner the {@link DocumentRunner#name()} that ran the documents
 * @param gitSha the commit the eval ran from, or {@code unknown}
 * @param modelIds the provider model IDs the documents' calls used, sorted
 * @param promptVersions the prompt versions the documents' calls used, sorted
 * @param usdInr the rupees per US dollar used to report cost in INR (NFR-4), or {@code null}
 */
public record RunConfig(
    String runId,
    Instant startedAt,
    String dataset,
    String strategy,
    String model,
    String runner,
    String gitSha,
    List<String> modelIds,
    List<String> promptVersions,
    BigDecimal usdInr) {

  public RunConfig {
    modelIds = List.copyOf(modelIds);
    promptVersions = List.copyOf(promptVersions);
  }
}
