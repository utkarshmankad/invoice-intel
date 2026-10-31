package dev.invoiceintel.validate;

import java.util.Objects;

/**
 * Why a document got its decision or score (VAL-8), one entry of {@code decision.reasons}. The
 * message may name rules, fields, classes and amounts, never pseudonym tokens or document text
 * (PII-8).
 *
 * @param ruleId the rule behind a {@link Code#RULE_FAIL} or {@link Code#RULE_WARN}, else {@code
 *     null}
 */
public record DecisionReason(Code code, String ruleId, String message) {

  /** Reason codes, stable for the review UI and the eval reports. */
  public enum Code {
    /** A rule failed; blocks auto-approval. */
    RULE_FAIL,
    /** A rule warned; lowers the score. */
    RULE_WARN,
    /** The Text stage's class hint and the LLM's {@code documentClass} differ. */
    CLASS_MISMATCH,
    /** The score is below the approve threshold. */
    LOW_CONFIDENCE,
    /** There is no extraction to decide on. */
    EXTRACTION_MISSING
  }

  public DecisionReason {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(message, "message");
    if ((code == Code.RULE_FAIL || code == Code.RULE_WARN) != (ruleId != null)) {
      throw new IllegalArgumentException("ruleId is set exactly for rule reasons");
    }
  }

  static DecisionReason of(Code code, String message) {
    return new DecisionReason(code, null, message);
  }
}
