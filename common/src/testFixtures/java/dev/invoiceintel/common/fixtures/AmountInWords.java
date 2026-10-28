package dev.invoiceintel.common.fixtures;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Writes an amount the way Indian invoices print it, using lakh and crore: {@code 123456.50} is
 * "One Lakh Twenty Three Thousand Four Hundred Fifty Six Rupees and Fifty Paise Only".
 */
public final class AmountInWords {

  private static final String[] ONES = {
    "Zero",
    "One",
    "Two",
    "Three",
    "Four",
    "Five",
    "Six",
    "Seven",
    "Eight",
    "Nine",
    "Ten",
    "Eleven",
    "Twelve",
    "Thirteen",
    "Fourteen",
    "Fifteen",
    "Sixteen",
    "Seventeen",
    "Eighteen",
    "Nineteen"
  };
  private static final String[] TENS = {
    "", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"
  };
  private static final long MAX_RUPEES = 99_99_99_99_999L;

  private AmountInWords() {}

  public static String of(BigDecimal amount) {
    BigDecimal value = amount.setScale(2, RoundingMode.HALF_UP);
    if (value.signum() < 0) {
      throw new IllegalArgumentException("amount must not be negative");
    }
    long rupees = value.longValue();
    if (rupees > MAX_RUPEES) {
      throw new IllegalArgumentException("amount too large");
    }
    int paise = value.remainder(BigDecimal.ONE).movePointRight(2).intValueExact();
    String words = (rupees == 0 ? "Zero" : rupeesInWords(rupees)) + " Rupees";
    if (paise > 0) {
      words += " and " + belowHundred(paise) + " Paise";
    }
    return words + " Only";
  }

  private static String rupeesInWords(long rupees) {
    StringBuilder out = new StringBuilder();
    long crore = rupees / 1_00_00_000;
    long rest = rupees % 1_00_00_000;
    if (crore > 0) {
      append(out, rupeesInWords(crore) + " Crore");
    }
    appendPart(out, rest / 1_00_000, "Lakh");
    appendPart(out, rest / 1_000 % 100, "Thousand");
    appendPart(out, rest / 100 % 10, "Hundred");
    if (rest % 100 > 0) {
      append(out, belowHundred((int) (rest % 100)));
    }
    return out.toString();
  }

  private static void appendPart(StringBuilder out, long count, String unit) {
    if (count > 0) {
      append(out, belowHundred((int) count) + " " + unit);
    }
  }

  private static void append(StringBuilder out, String words) {
    if (!out.isEmpty()) {
      out.append(' ');
    }
    out.append(words);
  }

  private static String belowHundred(int n) {
    if (n < 20) {
      return ONES[n];
    }
    return n % 10 == 0 ? TENS[n / 10] : TENS[n / 10] + " " + ONES[n % 10];
  }
}
