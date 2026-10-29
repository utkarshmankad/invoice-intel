package dev.invoiceintel.extract;

import dev.invoiceintel.common.DocumentId;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * One model call, as stored in {@code llm_call} (EXT-4, NFR-4, architecture.md §7). IDs, counts and
 * codes only, never prompt or response text (PII-8).
 *
 * @param attempt 1 for the first call, 2 for the repair retry
 * @param costUsd tokens × the registry's price per million (EXT-4)
 */
public record LlmCall(
    DocumentId documentId,
    String stage,
    String provider,
    String modelId,
    String promptVersion,
    int attempt,
    int inputTokens,
    int outputTokens,
    BigDecimal costUsd,
    long latencyMs,
    LlmOutcome outcome) {

  public LlmCall {
    Objects.requireNonNull(documentId, "documentId");
    Objects.requireNonNull(stage, "stage");
    Objects.requireNonNull(provider, "provider");
    Objects.requireNonNull(modelId, "modelId");
    Objects.requireNonNull(promptVersion, "promptVersion");
    Objects.requireNonNull(costUsd, "costUsd");
    Objects.requireNonNull(outcome, "outcome");
  }
}
