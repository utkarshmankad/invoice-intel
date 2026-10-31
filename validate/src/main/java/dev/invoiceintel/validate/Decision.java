package dev.invoiceintel.validate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * A document's VAL-8 decision, a {@code decision} row (architecture.md §6.6, §7).
 *
 * @param score 0–1 at 4 decimals, the value compared with the approve threshold
 * @param reasons every rule FAIL and WARN plus the scorer's own reasons, in that order
 */
public record Decision(BigDecimal score, DecisionOutcome outcome, List<DecisionReason> reasons) {

  public Decision {
    Objects.requireNonNull(score, "score");
    Objects.requireNonNull(outcome, "outcome");
    reasons = List.copyOf(reasons);
    if (score.signum() < 0 || score.compareTo(BigDecimal.ONE) > 0) {
      throw new IllegalArgumentException("score must be within 0–1");
    }
    if (outcome != DecisionOutcome.AUTO_APPROVED && reasons.isEmpty()) {
      throw new IllegalArgumentException(outcome + " needs a reason");
    }
  }
}
