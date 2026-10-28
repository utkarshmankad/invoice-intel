package dev.invoiceintel.common.fixtures;

import java.util.Random;

/**
 * Valid-format fake identifiers. Values are generated at run time, never written as literals, so
 * the repository's PII scan never sees an identifier-shaped string in source.
 */
public final class FakeIds {

  private static final String BASE36 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
  private static final String LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

  private FakeIds() {}

  /**
   * PAN: three letters, entity type ({@code P} person, {@code C} company, {@code F} firm), the
   * first letter of the name, four digits and a letter.
   */
  public static String pan(Random random, char entityType, char nameInitial) {
    return letters(random, 3)
        + entityType
        + Character.toUpperCase(nameInitial)
        + digits(random, 4)
        + letters(random, 1);
  }

  /** GSTIN: state code, PAN, entity number, {@code Z} and the mod-36 check character. */
  public static String gstin(GstState state, String pan, char entityNumber) {
    String body = state.code() + pan + entityNumber + 'Z';
    return body + gstinCheckChar(body);
  }

  /**
   * Check character of a 14-character GSTIN body: base-36 values weighted 1 and 2 alternately, each
   * product folded as {@code product / 36 + product % 36}.
   */
  public static char gstinCheckChar(String body) {
    if (body.length() != 14) {
      throw new IllegalArgumentException("GSTIN body must have 14 characters");
    }
    int sum = 0;
    for (int i = 0; i < body.length(); i++) {
      int value = BASE36.indexOf(body.charAt(i));
      if (value < 0) {
        throw new IllegalArgumentException("GSTIN body must be upper-case alphanumeric");
      }
      int product = value * (i % 2 == 0 ? 1 : 2);
      sum += product / 36 + product % 36;
    }
    return BASE36.charAt((36 - sum % 36) % 36);
  }

  /** Pre-GST VAT TIN: state code followed by nine digits. */
  public static String tin(Random random, GstState state) {
    return state.code() + digits(random, 9);
  }

  /** Indian mobile number in {@code +91 9XXXXXXXXX} form. */
  public static String mobile(Random random) {
    return "+91 " + (6 + random.nextInt(4)) + digits(random, 9);
  }

  /** Six-digit PIN code starting with the state's postal zone digit. */
  public static String pinCode(Random random, GstState state) {
    return state.pinPrefix() + digits(random, 5);
  }

  /** Marketplace order number in {@code NNN-NNNNNNN-NNNNNNN} form. */
  public static String orderNumber(Random random) {
    return digits(random, 3) + "-" + digits(random, 7) + "-" + digits(random, 7);
  }

  static String digits(Random random, int count) {
    StringBuilder out = new StringBuilder(count);
    for (int i = 0; i < count; i++) {
      out.append((char) ('0' + random.nextInt(10)));
    }
    return out.toString();
  }

  private static String letters(Random random, int count) {
    StringBuilder out = new StringBuilder(count);
    for (int i = 0; i < count; i++) {
      out.append(LETTERS.charAt(random.nextInt(LETTERS.length())));
    }
    return out.toString();
  }
}
