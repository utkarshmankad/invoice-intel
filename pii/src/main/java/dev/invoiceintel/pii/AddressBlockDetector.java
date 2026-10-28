package dev.invoiceintel.pii;

import dev.invoiceintel.pii.internal.IndianStates;
import dev.invoiceintel.pii.internal.Labels;
import dev.invoiceintel.pii.internal.PageLayout;
import dev.invoiceintel.text.TextLayer;
import dev.invoiceintel.text.TextPage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Layout layer (a) of PII detection (PII-2(a), architecture.md §6.3): the buyer's address blocks. A
 * block is the lines after a "Billing Address", "Shipping Address", "Ship To", "Bill To" or
 * "Deliver To" anchor in the anchor's column, up to the next known label or a vertical gap. The
 * first line is the buyer name (PERSON); later lines are ADDRESS, except that the buyer state is
 * kept (specs.md §6.1) and six-digit numbers are PIN codes. Lines labelled as phone or email are
 * typed as such rather than as ADDRESS. Spans that are seller data are dropped.
 */
public final class AddressBlockDetector implements PiiDetector {

  static final String PERSON = "address-block:name";
  static final String ADDRESS = "address-block:street";
  static final String PIN = "address-block:pin";
  static final String PHONE = "address-block:phone";
  static final String EMAIL = "address-block:email";

  // A buyer GSTIN/PAN line ends the block; the checksum and regex rules type those values.
  private static final List<String> STOPS =
      Stream.of(
              Labels.SECTION_LABELS,
              Labels.BUYER_ANCHORS,
              Labels.SELLER_ANCHORS,
              Labels.SELLER_ID_LABELS)
          .flatMap(List::stream)
          .toList();

  private static final Pattern STATE =
      Pattern.compile("(?i)\\b(?:" + IndianStates.alternation() + ")\\b");
  private static final Pattern PIN_WHOLE = Pattern.compile("[1-9]\\d{5}");
  private static final Pattern PIN_HEAD = Pattern.compile("[1-9]\\d{2}");
  private static final Pattern PIN_TAIL = Pattern.compile("\\d{3}");

  private record Typed(int word, PiiType type, String detector) {}

  @Override
  public List<PiiSpan> detect(TextLayer layer, DocumentContext ctx) {
    Objects.requireNonNull(ctx, "ctx");
    var spans = new ArrayList<PiiSpan>();
    for (TextPage page : layer.pages()) {
      PageText text = PageText.of(page);
      PageLayout layout = PageLayout.of(page);
      for (PageLayout.Block block : layout.blocks(Labels.BUYER_ANCHORS, STOPS, List.of())) {
        var typed = new ArrayList<Typed>();
        boolean first = true;
        for (List<Integer> line : block.lines()) {
          typeLine(layout, line, first, typed);
          first = false;
        }
        for (PiiSpan span : spans(text, typed)) {
          if (!ctx.isSellerData(span)) {
            spans.add(span);
          }
        }
      }
    }
    return spans;
  }

  private static void typeLine(
      PageLayout layout, List<Integer> line, boolean first, List<Typed> out) {
    String text = layout.text(line);
    int phoneEnd = Labels.prefixEnd(text, Labels.PHONE_LABELS);
    if (phoneEnd >= 0) {
      layout.wordsAfter(line, phoneEnd).forEach(w -> out.add(new Typed(w, PiiType.PHONE, PHONE)));
      return;
    }
    int emailEnd = Labels.prefixEnd(text, Labels.EMAIL_LABELS);
    if (emailEnd >= 0) {
      layout.wordsAfter(line, emailEnd).forEach(w -> out.add(new Typed(w, PiiType.EMAIL, EMAIL)));
      return;
    }
    if (first) {
      line.forEach(w -> out.add(new Typed(w, PiiType.PERSON, PERSON)));
      return;
    }
    boolean[] state = stateWords(layout, line, text);
    boolean pinTail = false;
    for (int i = 0; i < line.size(); i++) {
      int word = line.get(i);
      if (pinTail || isPin(layout, line, i)) {
        out.add(new Typed(word, PiiType.PIN_CODE, PIN));
        pinTail = !pinTail && !PIN_WHOLE.matcher(core(layout, word)).matches();
      } else if (!state[i]) {
        out.add(new Typed(word, PiiType.ADDRESS, ADDRESS));
      }
    }
  }

  // A word is the state if a state name covers all its letters and digits ("Karnataka," is, but
  // "Bengaluru-Karnataka" isn't, so the city part is not kept by accident).
  private static boolean[] stateWords(PageLayout layout, List<Integer> line, String text) {
    boolean[] state = new boolean[line.size()];
    Matcher m = STATE.matcher(text);
    while (m.find()) {
      int offset = 0;
      for (int i = 0; i < line.size(); i++) {
        String word = layout.word(line.get(i)).text();
        int end = offset + word.length();
        if (offset < m.end() && end > m.start()) {
          String before = word.substring(0, Math.max(0, m.start() - offset));
          String after = word.substring(Math.min(word.length(), m.end() - offset));
          state[i] |= !Labels.hasAlnum(before) && !Labels.hasAlnum(after);
        }
        offset = end + 1;
      }
    }
    return state;
  }

  // Six digits ("560034") or three and three ("560 034"); punctuation around them is ignored.
  private static boolean isPin(PageLayout layout, List<Integer> line, int i) {
    String word = core(layout, line.get(i));
    if (PIN_WHOLE.matcher(word).matches()) {
      return true;
    }
    return i + 1 < line.size()
        && PIN_HEAD.matcher(word).matches()
        && PIN_TAIL.matcher(core(layout, line.get(i + 1))).matches();
  }

  private static String core(PageLayout layout, int word) {
    String text = layout.word(word).text();
    int start = 0;
    int end = text.length();
    while (start < end && !Character.isLetterOrDigit(text.charAt(start))) {
      start++;
    }
    while (end > start && !Character.isLetterOrDigit(text.charAt(end - 1))) {
      end--;
    }
    return text.substring(start, end);
  }

  // Adjacent words of one type become one span; words that aren't adjacent in the page text
  // (another column's line lies between them) get separate spans.
  private static List<PiiSpan> spans(PageText text, List<Typed> typed) {
    var spans = new ArrayList<PiiSpan>();
    int i = 0;
    while (i < typed.size()) {
      Typed first = typed.get(i);
      int j = i;
      while (j + 1 < typed.size()
          && typed.get(j + 1).word() == typed.get(j).word() + 1
          && typed.get(j + 1).type() == first.type()) {
        j++;
      }
      spans.add(
          new PiiSpan(
              text.pageNo(),
              text.wordStart(first.word()),
              text.wordEnd(typed.get(j).word()),
              first.type(),
              first.detector()));
      i = j + 1;
    }
    return spans;
  }
}
