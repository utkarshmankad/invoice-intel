package dev.invoiceintel.validate;

import dev.invoiceintel.common.extraction.InvoiceExtraction;
import dev.invoiceintel.common.extraction.LineItem;
import dev.invoiceintel.common.extraction.TaxLine;
import dev.invoiceintel.common.extraction.TaxType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * VAL-2: every tax line's {@code amount} equals {@code rate}% of its line's {@code netAmount},
 * within ±₹1.00.
 *
 * <p>The base is the line's {@code netAmount}, i.e. after any discount: the taxable value GST is
 * charged on. The expected amount is {@code netAmount × rate / 100} rounded {@code HALF_UP} to 2
 * decimals. Lines without tax lines have nothing to check and pass. A tax line or base with a
 * missing value is a WARN (incomplete, not contradictory) unless another tax line fails. All
 * mismatches are listed in one FAIL message naming lines, tax types and amounts only (PII-8).
 */
public final class TaxAmountRule implements ValidationRule {

  public static final String ID = "VAL-2";

  private static final BigDecimal HUNDRED = new BigDecimal("100");

  @Override
  public String id() {
    return ID;
  }

  @Override
  public RuleResult check(InvoiceExtraction extraction, ValidationContext context) {
    List<LineItem> lines = extraction.lineItems();
    if (lines == null) {
      return RuleResult.pass();
    }
    var mismatches = new ArrayList<String>();
    var incomplete = new ArrayList<String>();
    for (int i = 0; i < lines.size(); i++) {
      LineItem line = lines.get(i);
      List<TaxLine> taxes = line.taxLines();
      if (taxes == null || taxes.isEmpty()) {
        continue;
      }
      String label = Amounts.lineLabel(line, i);
      BigDecimal base = Amounts.valueOf(line.netAmount());
      if (base == null) {
        incomplete.add(label + " has tax lines but no netAmount");
        continue;
      }
      for (TaxLine tax : taxes) {
        check(label, base, tax, mismatches, incomplete);
      }
    }
    if (!mismatches.isEmpty()) {
      return RuleResult.fail(String.join("; ", mismatches));
    }
    if (!incomplete.isEmpty()) {
      return RuleResult.warn(String.join("; ", incomplete));
    }
    return RuleResult.pass();
  }

  private static void check(
      String label,
      BigDecimal base,
      TaxLine tax,
      List<String> mismatches,
      List<String> incomplete) {
    TaxType type = Amounts.valueOf(tax.type());
    String name = label + " " + (type == null ? "tax" : type.name());
    BigDecimal rate = Amounts.valueOf(tax.rate());
    BigDecimal amount = Amounts.valueOf(tax.amount());
    if (rate == null || amount == null) {
      incomplete.add(name + " has no " + (rate == null ? "rate" : "amount"));
      return;
    }
    BigDecimal expected = Amounts.scale(base.multiply(rate).divide(HUNDRED));
    if (!Amounts.agree(expected, amount)) {
      mismatches.add(
          name
              + ": "
              + rate.stripTrailingZeros().toPlainString()
              + "% of "
              + Amounts.format(base)
              + " is "
              + Amounts.format(expected)
              + ", extracted amount "
              + Amounts.format(amount));
    }
  }
}
