package dev.invoiceintel.eval.run;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * What the model calls for one document cost and which model and prompt versions answered them
 * (EVAL-4, NFR-4), summed over the document's {@code llm_call} rows. IDs and amounts only.
 *
 * @param costUsd the cost of every model call, failed and repair calls included
 * @param modelIds the provider model IDs that were called
 * @param promptVersions the prompt versions used ({@code PromptRef.id()}, {@code name/v{n}})
 */
public record LlmUsage(BigDecimal costUsd, Set<String> modelIds, Set<String> promptVersions) {

  /** No model call. */
  public static final LlmUsage NONE = new LlmUsage(BigDecimal.ZERO, Set.of(), Set.of());

  public LlmUsage {
    Objects.requireNonNull(costUsd, "costUsd");
    if (costUsd.signum() < 0) {
      throw new IllegalArgumentException("costUsd must not be negative");
    }
    modelIds = Set.copyOf(modelIds);
    promptVersions = Set.copyOf(promptVersions);
  }

  /** Both usages together. */
  public LlmUsage plus(LlmUsage other) {
    Set<String> models = new TreeSet<>(modelIds);
    models.addAll(other.modelIds);
    Set<String> prompts = new TreeSet<>(promptVersions);
    prompts.addAll(other.promptVersions);
    return new LlmUsage(costUsd.add(other.costUsd), models, prompts);
  }
}
