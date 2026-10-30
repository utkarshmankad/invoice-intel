package dev.invoiceintel.validate;

/** Outcome of one validation rule (architecture.md §6.6); stored in {@code validation_result}. */
public enum RuleOutcome {
  PASS,
  /** Suspicious but not disqualifying; each WARN lowers the document score (VAL-8). */
  WARN,
  /** The extraction contradicts itself or the GST rules; blocks auto-approval (VAL-8). */
  FAIL
}
