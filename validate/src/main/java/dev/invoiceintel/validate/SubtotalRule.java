package dev.invoiceintel.validate;

import dev.invoiceintel.common.extraction.InvoiceExtraction;
import dev.invoiceintel.common.extraction.LineItem;
import java.math.BigDecimal;
import java.util.List;

/**
 * VAL-1: the line items' {@code netAmount}s add up to {@code subtotal}, within ±₹1.00.
 *
 * <p>Every line counts, whatever its {@code chargeType}: shipping and convenience charges are line
 * items (specs.md §5.2) and {@code subtotal} is the sum of net amounts (§5.1). A missing {@code
 * subtotal}, no line items or a line without {@code netAmount} make the sum uncheckable, which is a
 * WARN rather than a FAIL: the extraction is incomplete, not contradictory.
 */
public final class SubtotalRule implements ValidationRule {

  public static final String ID = "VAL-1";

  @Override
  public String id() {
    return ID;
  }

  @Override
  public RuleResult check(InvoiceExtraction extraction, ValidationContext context) {
    BigDecimal subtotal = Amounts.valueOf(extraction.subtotal());
    if (subtotal == null) {
      return RuleResult.warn("subtotal missing; cannot check line net amounts against it");
    }
    List<LineItem> lines = extraction.lineItems();
    if (lines == null || lines.isEmpty()) {
      return RuleResult.warn("no line items; cannot check subtotal");
    }
    BigDecimal sum = BigDecimal.ZERO;
    for (int i = 0; i < lines.size(); i++) {
      LineItem line = lines.get(i);
      BigDecimal net = Amounts.valueOf(line.netAmount());
      if (net == null) {
        return RuleResult.warn(
            Amounts.lineLabel(line, i) + " has no netAmount; cannot check subtotal");
      }
      sum = sum.add(net);
    }
    if (Amounts.agree(sum, subtotal)) {
      return RuleResult.pass();
    }
    return RuleResult.fail(
        "sum of line netAmount "
            + Amounts.format(sum)
            + " differs from subtotal "
            + Amounts.format(subtotal)
            + " by more than "
            + Amounts.format(Amounts.TOLERANCE));
  }
}
