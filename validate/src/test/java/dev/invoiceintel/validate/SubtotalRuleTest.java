package dev.invoiceintel.validate;

import static dev.invoiceintel.validate.ArithmeticFixtures.CONTEXT;
import static dev.invoiceintel.validate.ArithmeticFixtures.line;
import static dev.invoiceintel.validate.ArithmeticFixtures.withLines;
import static org.assertj.core.api.Assertions.assertThat;

import dev.invoiceintel.common.extraction.ChargeType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SubtotalRuleTest {

  private final SubtotalRule rule = new SubtotalRule();

  @Test
  void id_isVal1() {
    assertThat(rule.id()).isEqualTo("VAL-1");
  }

  @Test
  void check_consistentFixture_passes() {
    assertThat(rule.check(ExtractionFixtures.consistentTaxInvoice(), CONTEXT))
        .isEqualTo(RuleResult.pass());
  }

  @ParameterizedTest(name = "lines {0} + {1} vs subtotal {2} -> {3}")
  @CsvSource({
    "499.00, 40.00, 539.00, PASS",
    "499.00, 40.00, 540.00, PASS", // exactly +1.00 is within tolerance
    "499.00, 40.00, 538.00, PASS", // exactly -1.00 is within tolerance
    "499.00, 40.00, 540.01, FAIL",
    "499.00, 40.00, 537.99, FAIL",
    "499.004, 40.00, 540.00, PASS", // HALF_UP at 2 decimals: 539.00 vs 540.00
    "499.005, 40.00, 538.00, FAIL", // HALF_UP at 2 decimals: 539.01 vs 538.00
    "-499.00, -40.00, -539.50, PASS", // credit note amounts are negative
    "-499.00, -40.00, -541.00, FAIL",
    "0, 0, 0, PASS"
  })
  void check_comparesSumOfNetAmountsWithSubtotal(
      String net1, String net2, String subtotal, RuleOutcome expected) {
    var x = withLines(subtotal, List.of(line(1, net1), line(2, net2)));

    assertThat(rule.check(x, CONTEXT).outcome()).isEqualTo(expected);
  }

  @Test
  void check_countsChargeLines() {
    var x =
        withLines(
            "1080.00",
            List.of(
                line(1, ChargeType.PRODUCT, "1000.00"),
                line(2, ChargeType.SHIPPING, "40.00"),
                line(3, ChargeType.CONVENIENCE, "30.00"),
                line(4, ChargeType.OTHER, "10.00")));

    assertThat(rule.check(x, CONTEXT)).isEqualTo(RuleResult.pass());
  }

  @Test
  void check_mismatch_failsNamingAmountsOnly() {
    var x = withLines("1100.00", List.of(line(1, "1000.00"), line(2, "40")));

    assertThat(rule.check(x, CONTEXT))
        .isEqualTo(
            RuleResult.fail(
                "sum of line netAmount 1040.00 differs from subtotal 1100.00 by more than 1.00"));
  }

  @Test
  void check_missingSubtotal_warns() {
    var x = withLines(null, List.of(line(1, "1000.00")));

    assertThat(rule.check(x, CONTEXT))
        .isEqualTo(RuleResult.warn("subtotal missing; cannot check line net amounts against it"));
  }

  @Test
  void check_noLineItems_warns() {
    assertThat(rule.check(withLines("0", List.of()), CONTEXT))
        .isEqualTo(RuleResult.warn("no line items; cannot check subtotal"));
    assertThat(rule.check(withLines("0", null), CONTEXT).outcome()).isEqualTo(RuleOutcome.WARN);
  }

  @Test
  void check_lineWithoutNetAmount_warnsNamingTheLine() {
    var x = withLines("1000.00", List.of(line(1, "1000.00"), line(7, null)));

    assertThat(rule.check(x, CONTEXT))
        .isEqualTo(RuleResult.warn("line 7 has no netAmount; cannot check subtotal"));
  }

  @Test
  void check_lineWithoutLineNo_isNamedByPosition() {
    var x = withLines("1000.00", List.of(line(1, "1000.00"), line(null, ChargeType.OTHER, null)));

    assertThat(rule.check(x, CONTEXT).message()).startsWith("line 2 has no netAmount");
  }
}
