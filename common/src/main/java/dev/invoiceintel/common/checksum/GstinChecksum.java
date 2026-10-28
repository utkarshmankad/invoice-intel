package dev.invoiceintel.common.checksum;

import java.util.regex.Pattern;

/**
 * GSTIN format and mod-36 check character (VAL-4, PII-2(a)). A GSTIN is a two-digit state code, the
 * holder's PAN, an entity number, the letter {@code Z} and a check character.
 */
public final class GstinChecksum {

  private static final String BASE36 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

  /** State code, PAN (five letters, four digits, a letter), entity number, {@code Z}, check. */
  private static final Pattern FORMAT =
      Pattern.compile("\\d{2}[A-Z]{5}\\d{4}[A-Z][1-9A-Z]Z[0-9A-Z]");

  private GstinChecksum() {}

  /** Whether {@code gstin} has the GSTIN layout and a matching check character. */
  public static boolean isValid(CharSequence gstin) {
    return FORMAT.matcher(gstin).matches() && hasValidCheckChar(gstin);
  }

  /**
   * Whether {@code value} is 15 upper-case base-36 characters whose last is the check character of
   * the first 14. Unlike {@link #isValid}, the layout of the first 14 is not checked.
   */
  public static boolean hasValidCheckChar(CharSequence value) {
    if (value.length() != 15) {
      return false;
    }
    int sum = weightedSum(value.subSequence(0, 14));
    return sum >= 0 && BASE36.charAt(checkValue(sum)) == value.charAt(14);
  }

  /**
   * Check character of a 14-character GSTIN body: base-36 values weighted 1 and 2 alternately, each
   * product folded as {@code product / 36 + product % 36}.
   */
  public static char checkChar(CharSequence body) {
    if (body.length() != 14) {
      throw new IllegalArgumentException("GSTIN body must have 14 characters");
    }
    int sum = weightedSum(body);
    if (sum < 0) {
      throw new IllegalArgumentException("GSTIN body must be upper-case alphanumeric");
    }
    return BASE36.charAt(checkValue(sum));
  }

  private static int checkValue(int sum) {
    return (36 - sum % 36) % 36;
  }

  /** Folded weighted sum, or -1 if a character is not upper-case base-36. */
  private static int weightedSum(CharSequence body) {
    int sum = 0;
    for (int i = 0; i < body.length(); i++) {
      int value = BASE36.indexOf(body.charAt(i));
      if (value < 0) {
        return -1;
      }
      int product = value * (i % 2 == 0 ? 1 : 2);
      sum += product / 36 + product % 36;
    }
    return sum;
  }
}
