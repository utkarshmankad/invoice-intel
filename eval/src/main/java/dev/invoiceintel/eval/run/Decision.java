package dev.invoiceintel.eval.run;

/**
 * The VAL-8 decision of a document, as the eval sees it. Mirrors {@code
 * dev.invoiceintel.validate.DecisionOutcome}, which {@code eval} can't import (architecture.md
 * §4.1); the pipeline runner maps it by name.
 */
public enum Decision {
  /** No human touch needed. */
  AUTO_APPROVED,
  /** Sent to the review queue. */
  NEEDS_REVIEW,
  /** No extraction, or a PII leak (architecture.md §6.6). */
  FAILED
}
