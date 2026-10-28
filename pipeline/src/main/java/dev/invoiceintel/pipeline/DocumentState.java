package dev.invoiceintel.pipeline;

/**
 * Status of a document in the pipeline, the {@code document.status} column (architecture.md §5.1).
 * Declared in pipeline order, so a later constant is further along; {@link #FAILED} is outside that
 * order and records the failed stage separately.
 */
public enum DocumentState {
  RECEIVED,
  TEXT_READY,
  PII_CLEAN,
  EXTRACTED,
  VALIDATED,
  AUTO_APPROVED,
  NEEDS_REVIEW,
  REVIEWED,
  RECONCILED,
  FAILED
}
