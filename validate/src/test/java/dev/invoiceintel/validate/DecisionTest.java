package dev.invoiceintel.validate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.invoiceintel.validate.DecisionReason.Code;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class DecisionTest {

  private static final DecisionReason LOW = DecisionReason.of(Code.LOW_CONFIDENCE, "low");

  @ParameterizedTest
  @ValueSource(strings = {"-0.0001", "1.0001"})
  void constructor_scoreOutsideRange_isRejected(String score) {
    assertThatThrownBy(
            () -> new Decision(new BigDecimal(score), DecisionOutcome.NEEDS_REVIEW, List.of(LOW)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "1"})
  void constructor_scoreAtBounds_isAllowed(String score) {
    assertThat(
            new Decision(new BigDecimal(score), DecisionOutcome.AUTO_APPROVED, List.of()).score())
        .isEqualByComparingTo(score);
  }

  @ParameterizedTest
  @EnumSource(
      value = DecisionOutcome.class,
      names = {"NEEDS_REVIEW", "FAILED"})
  void constructor_reviewOrFailedWithoutReason_isRejected(DecisionOutcome outcome) {
    assertThatThrownBy(() -> new Decision(BigDecimal.ZERO, outcome, List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("needs a reason");
  }

  @Test
  void constructor_copiesReasons() {
    var reasons = new ArrayList<>(List.of(LOW));
    var decision = new Decision(BigDecimal.ZERO, DecisionOutcome.NEEDS_REVIEW, reasons);

    reasons.clear();

    assertThat(decision.reasons()).containsExactly(LOW);
  }

  @ParameterizedTest
  @EnumSource(
      value = Code.class,
      names = {"RULE_FAIL", "RULE_WARN"})
  void reason_ruleCodeWithoutRuleId_isRejected(Code code) {
    assertThatThrownBy(() -> new DecisionReason(code, null, "m"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @EnumSource(
      value = Code.class,
      names = {"CLASS_MISMATCH", "LOW_CONFIDENCE", "EXTRACTION_MISSING"})
  void reason_otherCodeWithRuleId_isRejected(Code code) {
    assertThatThrownBy(() -> new DecisionReason(code, "VAL-1", "m"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void reason_withoutMessage_isRejected() {
    assertThatThrownBy(() -> new DecisionReason(Code.RULE_FAIL, "VAL-1", null))
        .isInstanceOf(NullPointerException.class);
  }
}
