package dev.invoiceintel.validate;

import static dev.invoiceintel.validate.ArithmeticFixtures.CONTEXT;
import static dev.invoiceintel.validate.ArithmeticFixtures.line;
import static dev.invoiceintel.validate.ArithmeticFixtures.tax;
import static dev.invoiceintel.validate.ArithmeticFixtures.withLines;
import static org.assertj.core.api.Assertions.assertThat;

import dev.invoiceintel.common.extraction.ChargeType;
import dev.invoiceintel.common.extraction.TaxType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TaxAmountRuleTest {

  private final TaxAmountRule rule = new TaxAmountRule();

  @Test
  void id_isVal2() {
    assertThat(rule.id()).isEqualTo("VAL-2");
  }

  @Test
  void check_consistentFixture_passes() {
    assertThat(rule.check(ExtractionFixtures.consistentTaxInvoice(), CONTEXT))
        .isEqualTo(RuleResult.pass());
  }

  @ParameterizedTest(name = "{1}% of {0} vs {2} -> {3}")
  @CsvSource({
    "1000.00, 9, 90.00, PASS",
    "1000.00, 18, 180.00, PASS",
    "1000.00, 18, 181.00, PASS", // exactly +1.00 is within tolerance
    "1000.00, 18, 179.00, PASS", // exactly -1.00 is within tolerance
    "1000.00, 18, 181.01, FAIL",
    "1000.00, 18, 178.99, FAIL",
    "1000.00, 9, 180.00, FAIL", // 18% amount on a 9% line
    "423.73, 9, 38.14, PASS", // 38.1357 rounds HALF_UP to 38.14
    "847.46, 2.5, 21.19, PASS", // fractional rate: 21.1865 -> 21.19
    "1000.00, 0, 0.00, PASS",
    "1000.00, 0, 5.00, FAIL",
    "-1000.00, 9, -90.00, PASS", // credit note
    "-1000.00, 9, 90.00, FAIL"
  })
  void check_comparesRateTimesBaseWithAmount(
      String net, String rate, String amount, RuleOutcome expected) {
    var x = withLines(net, List.of(line(1, net, tax(TaxType.IGST, rate, amount))));

    assertThat(rule.check(x, CONTEXT).outcome()).isEqualTo(expected);
  }

  @Test
  void check_mismatches_failListingEveryOne() {
    var x =
        withLines(
            "1040.00",
            List.of(
                line(
                    1, "1000.00", tax(TaxType.CGST, "9", "90.00"), tax(TaxType.SGST, "9", "95.00")),
                line(2, ChargeType.SHIPPING, "40.00", tax(TaxType.IGST, "18", "3.60"))));

    assertThat(rule.check(x, CONTEXT))
        .isEqualTo(
            RuleResult.fail(
                "line 1 SGST: 9% of 1000.00 is 90.00, extracted amount 95.00; "
                    + "line 2 IGST: 18% of 40.00 is 7.20, extracted amount 3.60"));
  }

  @Test
  void check_lineWithoutTaxLines_passes() {
    var x = withLines("1000.00", List.of(line(1, "1000.00")));

    assertThat(rule.check(x, CONTEXT)).isEqualTo(RuleResult.pass());
  }

  @Test
  void check_noLineItems_passes() {
    assertThat(rule.check(withLines("0", null), CONTEXT)).isEqualTo(RuleResult.pass());
    assertThat(rule.check(withLines("0", List.of()), CONTEXT)).isEqualTo(RuleResult.pass());
  }

  @Test
  void check_missingBase_warns() {
    var x = withLines("0", List.of(line(3, null, tax(TaxType.IGST, "18", "18.00"))));

    assertThat(rule.check(x, CONTEXT))
        .isEqualTo(RuleResult.warn("line 3 has tax lines but no netAmount"));
  }

  @Test
  void check_missingRateOrAmount_warns() {
    var x =
        withLines(
            "1000.00",
            List.of(
                line(
                    1,
                    "1000.00",
                    tax(TaxType.CGST, null, "90.00"),
                    tax(TaxType.SGST, "9", null),
                    tax(null, null, null))));

    assertThat(rule.check(x, CONTEXT))
        .isEqualTo(
            RuleResult.warn(
                "line 1 CGST has no rate; line 1 SGST has no amount; line 1 tax has no rate"));
  }

  @Test
  void check_mismatchAndMissingValue_fails() {
    var x =
        withLines(
            "1000.00",
            List.of(
                line(
                    1,
                    "1000.00",
                    tax(TaxType.CGST, null, "90.00"),
                    tax(TaxType.SGST, "9", "99.00"))));

    assertThat(rule.check(x, CONTEXT).outcome()).isEqualTo(RuleOutcome.FAIL);
  }
}
