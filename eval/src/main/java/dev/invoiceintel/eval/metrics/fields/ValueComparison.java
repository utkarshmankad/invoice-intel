package dev.invoiceintel.eval.metrics.fields;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Normalized comparison of one expected and one extracted header value (architecture §6.9). */
public final class ValueComparison {

  private static final Pattern WHITESPACE =
      Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

  private ValueComparison() {}

  /**
   * Whether the extracted value matches the label. Both {@code null} is a match; exactly one {@code
   * null} is a mismatch (a null label against a non-null extraction counts as wrong, §9.2). A blank
   * string is treated as {@code null}.
   */
  public static boolean matches(HeaderField.Kind kind, String expected, String actual) {
    String e = blankToNull(expected);
    String a = blankToNull(actual);
    if (e == null || a == null) {
      return e == null && a == null;
    }
    return switch (kind) {
      case TEXT -> normalizeText(e).equals(normalizeText(a));
      case TOKEN -> e.equals(a);
      case DATE -> datesMatch(e, a);
      case AMOUNT -> amountsMatch(e, a);
    };
  }

  /** Trims, collapses runs of whitespace to one space and lower-cases (locale-independent). */
  public static String normalizeText(String value) {
    return WHITESPACE.matcher(value.strip()).replaceAll(" ").toLowerCase(Locale.ROOT);
  }

  private static boolean datesMatch(String e, String a) {
    try {
      return LocalDate.parse(e).equals(LocalDate.parse(a));
    } catch (DateTimeParseException ex) {
      return normalizeText(e).equals(normalizeText(a));
    }
  }

  private static boolean amountsMatch(String e, String a) {
    try {
      return new BigDecimal(e).compareTo(new BigDecimal(a)) == 0;
    } catch (NumberFormatException ex) {
      return normalizeText(e).equals(normalizeText(a));
    }
  }

  private static String blankToNull(String value) {
    return Objects.isNull(value) || value.isBlank() ? null : value.strip();
  }
}
