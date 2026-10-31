package dev.invoiceintel.validate;

/** The document decision of VAL-8 (architecture.md §6.6); stored in {@code decision.outcome}. */
public enum DecisionOutcome {
  /** Score at or above the approve threshold and no rule FAIL. */
  AUTO_APPROVED,
  /** Anything else with an extraction: a reviewer checks it (REV-1). */
  NEEDS_REVIEW,
  /** No extraction to decide on. */
  FAILED
}
