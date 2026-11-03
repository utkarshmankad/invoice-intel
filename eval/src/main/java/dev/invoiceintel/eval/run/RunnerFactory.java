package dev.invoiceintel.eval.run;

/** Creates the {@link DocumentRunner} for a run's strategy and model. */
@FunctionalInterface
public interface RunnerFactory {

  /**
   * The runner for this configuration.
   *
   * @param strategy the extraction strategy name
   * @param model the model ID
   */
  DocumentRunner create(String strategy, String model);
}
