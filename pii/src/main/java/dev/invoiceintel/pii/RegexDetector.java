package dev.invoiceintel.pii;

import dev.invoiceintel.pii.internal.IndianStates;
import dev.invoiceintel.text.TextLayer;
import dev.invoiceintel.text.TextPage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rule layer (a) of PII detection (PII-2(a), architecture.md §6.3): regular expressions for phone,
 * email, PIN code, PAN, order and invoice numbers. GSTIN and Aadhaar need checksums (plan M2.2).
 * Biased toward recall (specs.md §6.3); seller-side values are excluded later by seller-block
 * tagging (M2.3), not here. Patterns avoid nested quantifiers (ReDoS).
 */
public final class RegexDetector implements PiiDetector {

  /** One pattern; the span is capture group 1 if the pattern has one, else the whole match. */
  record Rule(String name, PiiType type, Pattern pattern) {}

  // Starts and ends that keep a match from being part of a longer number or code.
  private static final String NOT_AFTER = "(?<![\\w/@.-])";
  private static final String NOT_BEFORE = "(?![\\w/@-])";

  // Document numbers after a label: letters, digits, '/', '-', at least one digit.
  private static final String DOC_NUMBER =
      "((?=[A-Za-z0-9/-]{0,40}\\d)[A-Za-z0-9][A-Za-z0-9/-]{3,40})";
  private static final String LABEL_END = "\\s*[:.#-]{0,3}\\s*";

  // Six digits, first digit 1-9, not followed by more digits or decimals ("560 001" also counts
  // after a label or state).
  private static final String PIN_END = "(?!\\d|[.,]\\d)";
  private static final String PIN = "([1-9]\\d{2}\\s?\\d{3})" + PIN_END;
  private static final String PIN_COMPACT = "([1-9]\\d{5})" + PIN_END;

  static final List<Rule> RULES =
      List.of(
          new Rule(
              "regex:email",
              PiiType.EMAIL,
              Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")),
          // Mobile: optional +91 / 91 / 0, ten digits from 6-9, optionally split 5+5.
          new Rule(
              "regex:mobile",
              PiiType.PHONE,
              Pattern.compile(
                  NOT_AFTER
                      + "(?:\\+91[\\s-]|\\+91|91[\\s-]|91|0)?[6-9]\\d{4}[\\s-]?\\d{5}"
                      + NOT_BEFORE)),
          // Landline: 0 or +91, STD code of 2-4 digits, subscriber number to ten digits in all.
          new Rule(
              "regex:landline",
              PiiType.PHONE,
              Pattern.compile(
                  NOT_AFTER
                      + "(?:0|\\+91[\\s-]?)(?:\\d{2}[\\s-]?\\d{8}|\\d{3}[\\s-]?\\d{7}|\\d{4}[\\s-]?\\d{6})"
                      + NOT_BEFORE)),
          // PIN codes need context; a bare six-digit number is as likely an HSN code. The address
          // block detector (M2.3) covers PINs in the buyer address without a label or state.
          new Rule(
              "regex:pin-label",
              PiiType.PIN_CODE,
              Pattern.compile(
                  "(?i:\\b(?:pin\\s*code|pincode|pin|postal\\s*code|zip)\\b)" + LABEL_END + PIN)),
          new Rule(
              "regex:pin-state",
              PiiType.PIN_CODE,
              Pattern.compile(
                  "(?i:\\b(?:" + IndianStates.alternation() + ")\\b)[\\s,.-]{0,3}" + PIN)),
          // Two-column layouts (seller | buyer) interleave lines, so a buyer PIN can follow the
          // seller's PIN instead of a state name.
          new Rule(
              "regex:pin-state-next",
              PiiType.PIN_CODE,
              Pattern.compile(
                  "(?i:\\b(?:"
                      + IndianStates.alternation()
                      + ")\\b)[\\s,.-]{0,3}[1-9]\\d{5}\\s"
                      + PIN_COMPACT)),
          new Rule(
              "regex:pin-city",
              PiiType.PIN_CODE,
              Pattern.compile("(?<=[A-Za-z])\\s?-\\s?" + PIN_COMPACT)),
          // Individual and company PANs alike; the fourth letter is the holder type.
          new Rule(
              "regex:pan",
              PiiType.PAN,
              Pattern.compile(
                  "(?<![A-Za-z0-9])[A-Z]{3}[ABCFGHJLPT][A-Z]\\d{4}[A-Z](?![A-Za-z0-9])")),
          // Marketplace order numbers: NNN-NNNNNNN-NNNNNNN.
          new Rule(
              "regex:order-number",
              PiiType.ORDER_NUMBER,
              Pattern.compile("(?<![\\w-])\\d{3}-\\d{7}-\\d{7}(?![\\w-])")),
          new Rule(
              "regex:order-label",
              PiiType.ORDER_NUMBER,
              Pattern.compile(
                  "(?i:\\border\\s*(?:number|no\\b|id\\b|#))" + LABEL_END + DOC_NUMBER)),
          new Rule(
              "regex:invoice-label",
              PiiType.INVOICE_NUMBER,
              Pattern.compile(
                  "(?i:\\b(?:invoice|credit\\s*note|debit\\s*note|bill)\\s*(?:number|no\\b|num\\b|#))"
                      + LABEL_END
                      + DOC_NUMBER)));

  @Override
  public List<PiiSpan> detect(TextLayer layer, DocumentContext ctx) {
    Objects.requireNonNull(ctx, "ctx");
    var spans = new ArrayList<PiiSpan>();
    for (TextPage page : layer.pages()) {
      String text = PageText.of(page).text();
      for (Rule rule : RULES) {
        Matcher m = rule.pattern().matcher(text);
        int group = m.groupCount() >= 1 ? 1 : 0;
        while (m.find()) {
          spans.add(
              new PiiSpan(page.pageNo(), m.start(group), m.end(group), rule.type(), rule.name()));
        }
      }
    }
    return spans;
  }
}
