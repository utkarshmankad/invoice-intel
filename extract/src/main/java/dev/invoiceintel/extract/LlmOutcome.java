package dev.invoiceintel.extract;

/** Outcome of one model call, stored as {@code llm_call.outcome} (EXT-4). */
public enum LlmOutcome {
  /** The response parsed and passed Bean Validation. */
  SUCCEEDED,
  /** The response did not parse or failed Bean Validation (EXT-1). */
  INVALID_OUTPUT,
  /** The provider call failed. */
  ERROR
}
