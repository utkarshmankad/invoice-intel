package dev.invoiceintel.validate;

import java.util.Objects;

/**
 * What a {@link ValidationRule} found. A WARN or FAIL carries a message for the reviewer (REV-2);
 * it may name fields and amounts, never pseudonym tokens or other text from the document (PII-8).
 */
public record RuleResult(RuleOutcome outcome, String message) {

  public RuleResult {
    Objects.requireNonNull(outcome, "outcome");
    if (outcome == RuleOutcome.PASS) {
      if (message != null) {
        throw new IllegalArgumentException("a PASS has no message");
      }
    } else if (message == null || message.isBlank()) {
      throw new IllegalArgumentException(outcome + " needs a message");
    }
  }

  public static RuleResult pass() {
    return new RuleResult(RuleOutcome.PASS, null);
  }

  public static RuleResult warn(String message) {
    return new RuleResult(RuleOutcome.WARN, message);
  }

  public static RuleResult fail(String message) {
    return new RuleResult(RuleOutcome.FAIL, message);
  }
}
