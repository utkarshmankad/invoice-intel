package dev.invoiceintel.app.logging;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Last-line masking of PII-shaped strings in log output (PII-8, architecture.md §11). Code logs
 * only IDs, stages and codes; this backstop catches mistakes. The pattern list is a stub until the
 * PII detectors (M2.1) define the full regex set. Biased toward recall: an over-masked log line is
 * harmless, a leaked phone number is not.
 */
public final class PiiLogMasker {

  private record Rule(Pattern pattern, String replacement) {}

  // Order matters: emails first, so digits inside an address are not masked as a phone.
  private static final List<Rule> RULES =
      List.of(
          new Rule(Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "[EMAIL]"),
          // Indian mobile numbers: optional +91 / 91 / 0 prefix, 10 digits starting 6-9,
          // optionally split 5+5 by a space or hyphen. No nested quantifiers (ReDoS, FindSecBugs).
          new Rule(
              Pattern.compile(
                  "(?<!\\d)(?:\\+91[\\s-]|\\+91|91[\\s-]|91|0)?[6-9]\\d{4}[\\s-]?\\d{5}(?!\\d)"),
              "[PHONE]"));

  private PiiLogMasker() {}

  /** Returns {@code value} with every PII-shaped substring replaced by a type placeholder. */
  public static String mask(String value) {
    if (value == null || value.isEmpty()) {
      return value;
    }
    String masked = value;
    for (Rule rule : RULES) {
      masked = rule.pattern().matcher(masked).replaceAll(rule.replacement());
    }
    return masked;
  }
}
