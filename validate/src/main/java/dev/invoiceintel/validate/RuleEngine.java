package dev.invoiceintel.validate;

import dev.invoiceintel.common.extraction.InvoiceExtraction;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Runs every {@link ValidationRule} on an extraction, in registration order (architecture.md §6.6).
 * A rule that throws counts as a FAIL, so a broken rule sends the document to review instead of
 * failing the stage or being skipped; the exception's text is dropped, since it may quote values
 * (PII-8).
 */
public final class RuleEngine {

  static final String RULE_ERROR = "Rule could not be evaluated";

  private final List<ValidationRule> rules;

  public RuleEngine(List<? extends ValidationRule> rules) {
    this.rules = List.copyOf(rules);
    var ids = new HashSet<String>();
    for (ValidationRule rule : this.rules) {
      if (!ids.add(Objects.requireNonNull(rule.id(), "rule id"))) {
        throw new IllegalArgumentException("duplicate rule id " + rule.id());
      }
    }
  }

  public List<ValidationResult> run(InvoiceExtraction extraction, ValidationContext context) {
    Objects.requireNonNull(extraction, "extraction");
    Objects.requireNonNull(context, "context");
    return rules.stream().map(rule -> check(rule, extraction, context)).toList();
  }

  private static ValidationResult check(
      ValidationRule rule, InvoiceExtraction extraction, ValidationContext context) {
    RuleResult result;
    try {
      result = Objects.requireNonNull(rule.check(extraction, context), "rule result");
    } catch (RuntimeException e) {
      result = RuleResult.fail(RULE_ERROR);
    }
    return ValidationResult.of(rule.id(), result);
  }
}
