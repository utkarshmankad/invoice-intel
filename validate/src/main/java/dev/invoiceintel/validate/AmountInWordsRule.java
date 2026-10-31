package dev.invoiceintel.validate;

import dev.invoiceintel.common.extraction.InvoiceExtraction;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * VAL-7: {@code amountInWords}, when present, states {@code grandTotal}, within ±₹1.00.
 *
 * <p>The words are parsed with Indian numbering ({@link AmountInWords}). The tolerance is the one
 * VAL-1..VAL-3 use, so words that leave out the paise of a total still pass. Words without a sign
 * are compared with the total's magnitude, since credit notes usually print the amount unsigned. No
 * words is a PASS (the field is optional); words that can't be parsed, or no total to compare them
 * with, is a WARN. Messages never quote the words (PII-8), only the amounts.
 */
public final class AmountInWordsRule implements ValidationRule {

  public static final String ID = "VAL-7";

  @Override
  public String id() {
    return ID;
  }

  @Override
  public RuleResult check(InvoiceExtraction extraction, ValidationContext context) {
    String words = Amounts.valueOf(extraction.amountInWords());
    if (words == null || words.isBlank()) {
      return RuleResult.pass();
    }
    BigDecimal grandTotal = Amounts.valueOf(extraction.grandTotal());
    if (grandTotal == null) {
      return RuleResult.warn("grandTotal missing; cannot check amountInWords");
    }
    Optional<BigDecimal> parsed = AmountInWords.parse(words);
    if (parsed.isEmpty()) {
      return RuleResult.warn("amountInWords could not be read as an amount");
    }
    BigDecimal stated = parsed.get();
    BigDecimal expected = stated.signum() < 0 ? grandTotal : grandTotal.abs();
    if (Amounts.agree(stated, expected)) {
      return RuleResult.pass();
    }
    return RuleResult.fail(
        "amountInWords states "
            + Amounts.format(stated)
            + ", differs from grandTotal "
            + Amounts.format(grandTotal)
            + " by more than "
            + Amounts.format(Amounts.TOLERANCE));
  }
}
