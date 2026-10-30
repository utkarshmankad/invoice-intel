package dev.invoiceintel.validate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class RuleResultTest {

  @Test
  void pass_hasNoMessage() {
    assertThat(RuleResult.pass()).isEqualTo(new RuleResult(RuleOutcome.PASS, null));
  }

  @Test
  void warnAndFail_keepTheirMessage() {
    assertThat(RuleResult.warn("w")).isEqualTo(new RuleResult(RuleOutcome.WARN, "w"));
    assertThat(RuleResult.fail("f")).isEqualTo(new RuleResult(RuleOutcome.FAIL, "f"));
  }

  @Test
  void passWithMessage_isRejected() {
    assertThatThrownBy(() -> new RuleResult(RuleOutcome.PASS, "fine"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = " ")
  void warnWithoutMessage_isRejected(String message) {
    assertThatThrownBy(() -> RuleResult.warn(message)).isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @NullAndEmptySource
  void failWithoutMessage_isRejected(String message) {
    assertThatThrownBy(() -> RuleResult.fail(message)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nullOutcome_isRejected() {
    assertThatThrownBy(() -> new RuleResult(null, "m")).isInstanceOf(NullPointerException.class);
  }
}
