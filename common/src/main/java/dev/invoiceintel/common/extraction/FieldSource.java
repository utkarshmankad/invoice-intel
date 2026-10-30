package dev.invoiceintel.common.extraction;

/** Where a field's value came from (specs.md §5.3). */
public enum FieldSource {
  /** Returned by the extraction LLM. */
  LLM,
  /** Derived or corrected by a deterministic rule. */
  RULE,
  /** Entered or corrected by a reviewer (REV-3). */
  HUMAN
}
