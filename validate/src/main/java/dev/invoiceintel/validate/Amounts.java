package dev.invoiceintel.validate;

import dev.invoiceintel.common.extraction.Field;
import dev.invoiceintel.common.extraction.LineItem;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Money helpers shared by the arithmetic rules VAL-1..VAL-3 (architecture.md §6.6): amounts are
 * {@code BigDecimal} at 2 decimals, {@code HALF_UP}, and agree when they differ by at most ₹1.00.
 */
final class Amounts {

  /** Inclusive tolerance of VAL-1..VAL-3: a difference of exactly ₹1.00 still passes. */
  static final BigDecimal TOLERANCE = new BigDecimal("1.00");

  private Amounts() {}

  static BigDecimal scale(BigDecimal amount) {
    return amount.setScale(2, RoundingMode.HALF_UP);
  }

  /** The amount at 2 decimals as plain text, for reviewer messages. */
  static String format(BigDecimal amount) {
    return scale(amount).toPlainString();
  }

  /** Whether {@code expected} and {@code actual} differ by at most {@link #TOLERANCE}. */
  static boolean agree(BigDecimal expected, BigDecimal actual) {
    return scale(expected).subtract(scale(actual)).abs().compareTo(TOLERANCE) <= 0;
  }

  /** The field's value, or {@code null} when the field is absent. */
  static <T> T valueOf(Field<T> field) {
    return field == null ? null : field.value();
  }

  /**
   * How a message names a line: its extracted {@code lineNo}, else its 1-based position. Line
   * numbers are not document text, so messages may carry them (PII-8).
   */
  static String lineLabel(LineItem line, int index) {
    Integer lineNo = valueOf(line.lineNo());
    return "line " + (lineNo == null ? index + 1 : lineNo);
  }
}
