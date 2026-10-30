package dev.invoiceintel.validate;

import dev.invoiceintel.common.extraction.InvoiceExtraction;
import java.math.BigDecimal;
import java.util.ArrayList;

/**
 * VAL-3: {@code subtotal + totalTax} equals {@code grandTotal}, within ±₹1.00.
 *
 * <p>The spec's "(+ charges)" adds no separate term here. The schema has no charges field:
 * shipping, convenience and other charges are line items with a non-{@code PRODUCT} {@code
 * chargeType} (specs.md §5.2), so their net amounts are already part of {@code subtotal} (checked
 * by VAL-1) and their taxes part of {@code totalTax}; adding them again would count them twice. A
 * missing total is a WARN: the extraction is incomplete, not contradictory.
 */
public final class GrandTotalRule implements ValidationRule {

  public static final String ID = "VAL-3";

  @Override
  public String id() {
    return ID;
  }

  @Override
  public RuleResult check(InvoiceExtraction extraction, ValidationContext context) {
    BigDecimal subtotal = Amounts.valueOf(extraction.subtotal());
    BigDecimal totalTax = Amounts.valueOf(extraction.totalTax());
    BigDecimal grandTotal = Amounts.valueOf(extraction.grandTotal());
    var missing = new ArrayList<String>();
    if (subtotal == null) {
      missing.add("subtotal");
    }
    if (totalTax == null) {
      missing.add("totalTax");
    }
    if (grandTotal == null) {
      missing.add("grandTotal");
    }
    if (!missing.isEmpty()) {
      return RuleResult.warn(String.join(", ", missing) + " missing; cannot check grand total");
    }
    BigDecimal expected = subtotal.add(totalTax);
    if (Amounts.agree(expected, grandTotal)) {
      return RuleResult.pass();
    }
    return RuleResult.fail(
        "subtotal + totalTax = "
            + Amounts.format(expected)
            + " differs from grandTotal "
            + Amounts.format(grandTotal)
            + " by more than "
            + Amounts.format(Amounts.TOLERANCE));
  }
}
