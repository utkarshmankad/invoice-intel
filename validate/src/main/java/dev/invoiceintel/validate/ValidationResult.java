package dev.invoiceintel.validate;

import java.util.Objects;

/** One rule's result for one extraction, a {@code validation_result} row (architecture.md §7). */
public record ValidationResult(String ruleId, RuleOutcome outcome, String message) {

  public ValidationResult {
    Objects.requireNonNull(ruleId, "ruleId");
    Objects.requireNonNull(outcome, "outcome");
  }

  static ValidationResult of(String ruleId, RuleResult result) {
    return new ValidationResult(ruleId, result.outcome(), result.message());
  }
}
