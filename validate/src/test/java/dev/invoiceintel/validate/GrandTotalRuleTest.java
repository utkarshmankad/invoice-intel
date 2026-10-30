package dev.invoiceintel.validate;

import static dev.invoiceintel.validate.ArithmeticFixtures.CONTEXT;
import static dev.invoiceintel.validate.ArithmeticFixtures.line;
import static dev.invoiceintel.validate.ArithmeticFixtures.tax;
import static dev.invoiceintel.validate.ArithmeticFixtures.with;
import static dev.invoiceintel.validate.ArithmeticFixtures.withTotals;
import static org.assertj.core.api.Assertions.assertThat;

import dev.invoiceintel.common.extraction.ChargeType;
import dev.invoiceintel.common.extraction.TaxType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GrandTotalRuleTest {

  private final GrandTotalRule rule = new GrandTotalRule();

  @Test
  void id_isVal3() {
    assertThat(rule.id()).isEqualTo("VAL-3");
  }

  @Test
  void check_consistentFixture_passes() {
    assertThat(rule.check(ExtractionFixtures.consistentTaxInvoice(), CONTEXT))
        .isEqualTo(RuleResult.pass());
  }

  @ParameterizedTest(name = "{0} + {1} vs {2} -> {3}")
  @CsvSource({
    "1000.00, 180.00, 1180.00, PASS",
    "1000.00, 180.00, 1181.00, PASS", // exactly +1.00 is within tolerance
    "1000.00, 180.00, 1179.00, PASS", // exactly -1.00 is within tolerance
    "1000.00, 180.00, 1181.01, FAIL",
    "1000.00, 180.00, 1178.99, FAIL",
    "1000.00, 180.00, 1000.00, FAIL", // tax left out of the grand total
    "999.995, 180.00, 1181.00, PASS", // HALF_UP at 2 decimals: 1180.00 vs 1181.00
    "1000.00, 0.00, 1000.00, PASS",
    "-1000.00, -180.00, -1180.00, PASS", // credit note
    "-1000.00, -180.00, 1180.00, FAIL"
  })
  void check_comparesSubtotalPlusTaxWithGrandTotal(
      String subtotal, String totalTax, String grandTotal, RuleOutcome expected) {
    assertThat(rule.check(withTotals(subtotal, totalTax, grandTotal), CONTEXT).outcome())
        .isEqualTo(expected);
  }

  @Test
  void check_chargeLinesAreNotAddedTwice() {
    // 1000 product + 40 shipping, each taxed at 18%: subtotal 1040, tax 187.20, total 1227.20.
    var lines =
        List.of(
            line(1, ChargeType.PRODUCT, "1000.00", tax(TaxType.IGST, "18", "180.00")),
            line(2, ChargeType.SHIPPING, "40.00", tax(TaxType.IGST, "18", "7.20")));

    assertThat(rule.check(with("1040.00", "187.20", "1227.20", lines), CONTEXT))
        .isEqualTo(RuleResult.pass());
  }

  @Test
  void check_mismatch_failsNamingAmountsOnly() {
    assertThat(rule.check(withTotals("1000.00", "180", "1200.00"), CONTEXT))
        .isEqualTo(
            RuleResult.fail(
                "subtotal + totalTax = 1180.00 differs from grandTotal 1200.00 by more than 1.00"));
  }

  @ParameterizedTest(name = "{0}/{1}/{2} -> {3}")
  @CsvSource(
      nullValues = "null",
      value = {
        "null, 180.00, 1180.00, 'subtotal missing; cannot check grand total'",
        "1000.00, null, 1180.00, 'totalTax missing; cannot check grand total'",
        "1000.00, 180.00, null, 'grandTotal missing; cannot check grand total'",
        "null, null, null, 'subtotal, totalTax, grandTotal missing; cannot check grand total'"
      })
  void check_missingTotal_warns(
      String subtotal, String totalTax, String grandTotal, String message) {
    assertThat(rule.check(withTotals(subtotal, totalTax, grandTotal), CONTEXT))
        .isEqualTo(RuleResult.warn(message));
  }
}
