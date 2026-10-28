package dev.invoiceintel.pii;

import dev.invoiceintel.common.checksum.GstinChecksum;
import dev.invoiceintel.common.checksum.Verhoeff;
import dev.invoiceintel.text.TextLayer;
import dev.invoiceintel.text.TextPage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checksum part of rule layer (a) (PII-2(a), architecture.md §6.3): GSTINs and Aadhaar numbers. A
 * value with the right shape is reported only when its check character passes ({@code
 * checksum:gstin}, {@code checksum:aadhaar}), which keeps HSN codes, document numbers and amounts
 * out. For recall (specs.md §6.3), a 15-character code right after a GSTIN label is reported even
 * when its checksum fails, since that is most likely a misread GSTIN ({@code regex:gstin-label}).
 * Seller GSTINs are found too; seller-block tagging (M2.3) removes them. Patterns avoid nested
 * quantifiers (ReDoS).
 */
public final class ChecksumDetector implements PiiDetector {

  static final String GSTIN_RULE = "checksum:gstin";
  static final String AADHAAR_RULE = "checksum:aadhaar";
  static final String GSTIN_LABEL_RULE = "regex:gstin-label";

  // State code, PAN, entity number, Z, check character; not part of a longer code.
  private static final Pattern GSTIN =
      Pattern.compile("(?<![A-Za-z0-9])\\d{2}[A-Z]{5}\\d{4}[A-Z][0-9A-Z]Z[0-9A-Z](?![A-Za-z0-9])");

  // Twelve digits, the first 2-9, optionally split 4-4-4 by spaces or hyphens; not part of a
  // longer number or a decimal.
  private static final Pattern AADHAAR =
      Pattern.compile("(?<![\\w/@.,-])[2-9]\\d{3}[ -]?\\d{4}[ -]?\\d{4}(?![\\w/@-])(?![.,]\\d)");

  // "GST", "GSTIN" (also "GSTIN/UIN", "GST No", "GST Registration No"); not CGST/SGST/IGST.
  private static final Pattern GSTIN_LABEL =
      Pattern.compile("(?<![A-Za-z0-9])(?i:gstin|gst)(?![A-Za-z0-9])");

  // A 15-character code with a digit among its characters, after a label.
  private static final Pattern LABELLED_CODE =
      Pattern.compile("(?<![A-Za-z0-9])(?=[A-Za-z]{0,14}\\d)[0-9A-Za-z]{15}(?![A-Za-z0-9])");

  /** How far after a label its value may start ("GSTIN/UIN Registration No. : " fits). */
  private static final int LABEL_REACH = 30;

  @Override
  public List<PiiSpan> detect(TextLayer layer, DocumentContext ctx) {
    Objects.requireNonNull(ctx, "ctx");
    var spans = new ArrayList<PiiSpan>();
    for (TextPage page : layer.pages()) {
      String text = PageText.of(page).text();
      detectGstins(page.pageNo(), text, spans);
      detectAadhaars(page.pageNo(), text, spans);
    }
    return spans;
  }

  private static void detectGstins(int pageNo, String text, List<PiiSpan> spans) {
    Set<Integer> starts = new HashSet<>();
    Matcher m = GSTIN.matcher(text);
    while (m.find()) {
      if (GstinChecksum.isValid(m.group())) {
        spans.add(new PiiSpan(pageNo, m.start(), m.end(), PiiType.GSTIN, GSTIN_RULE));
        starts.add(m.start());
      }
    }
    Matcher label = GSTIN_LABEL.matcher(text);
    Matcher code = LABELLED_CODE.matcher(text).useTransparentBounds(true);
    while (label.find()) {
      int from = label.end();
      code.region(from, Math.min(text.length(), from + LABEL_REACH + 15));
      // The first code after the label, with only words and punctuation in between.
      if (code.find()
          && code.start() - from <= LABEL_REACH
          && text.substring(from, code.start()).chars().noneMatch(Character::isDigit)
          && starts.add(code.start())) {
        spans.add(new PiiSpan(pageNo, code.start(), code.end(), PiiType.GSTIN, GSTIN_LABEL_RULE));
      }
    }
  }

  private static void detectAadhaars(int pageNo, String text, List<PiiSpan> spans) {
    Matcher m = AADHAAR.matcher(text);
    while (m.find()) {
      String digits = m.group().replace(" ", "").replace("-", "");
      if (Verhoeff.isValid(digits)) {
        spans.add(new PiiSpan(pageNo, m.start(), m.end(), PiiType.AADHAAR, AADHAAR_RULE));
      }
    }
  }
}
