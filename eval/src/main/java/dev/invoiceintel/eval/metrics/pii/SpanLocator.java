package dev.invoiceintel.eval.metrics.pii;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds every occurrence of a label span's text in its page's text (architecture.md §9.2), so
 * labellers never deal with character offsets.
 *
 * <p>Both texts are compared with whitespace runs collapsed to one space and case folded per
 * character; the label text is also trimmed. Occurrences don't overlap, and a match must not cut a
 * word: when the label text starts (ends) with a letter or digit, the page character before (after)
 * the match must not be one, so a name like "Ravi" isn't found inside "Ravindra". Offsets are
 * mapped back to the original page text: {@code start} is the first matched character and {@code
 * end} is one past the last, so whitespace inside a match keeps its original length.
 */
public final class SpanLocator {

  private SpanLocator() {}

  /** Every occurrence of {@code label}'s text in {@code pageText}, in page order; may be empty. */
  public static List<PageSpan> locate(LabelSpan label, String pageText) {
    String needle = normalize(label.text(), null).strip();
    List<PageSpan> found = new ArrayList<>();
    if (needle.isEmpty()) {
      return found;
    }
    int[] origin = new int[pageText.length()];
    String hay = normalize(pageText, origin);
    boolean wordStart = Character.isLetterOrDigit(needle.charAt(0));
    boolean wordEnd = Character.isLetterOrDigit(needle.charAt(needle.length() - 1));
    int from = 0;
    while (true) {
      int at = hay.indexOf(needle, from);
      if (at < 0) {
        return found;
      }
      int after = at + needle.length();
      boolean cutsBefore = wordStart && at > 0 && Character.isLetterOrDigit(hay.charAt(at - 1));
      boolean cutsAfter =
          wordEnd && after < hay.length() && Character.isLetterOrDigit(hay.charAt(after));
      if (cutsBefore || cutsAfter) {
        from = at + 1;
      } else {
        found.add(new PageSpan(label.type(), label.page(), origin[at], origin[after - 1] + 1));
        from = after;
      }
    }
  }

  /**
   * Collapses whitespace runs to one space and lower-cases each character (one char in, at most one
   * char out, so offsets map back). When {@code origin} is given, {@code origin[i]} receives the
   * index in {@code text} of normalized character {@code i}.
   */
  private static String normalize(String text, int[] origin) {
    StringBuilder out = new StringBuilder(text.length());
    boolean inSpace = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
        if (inSpace) {
          continue;
        }
        inSpace = true;
        c = ' ';
      } else {
        inSpace = false;
        c = Character.toLowerCase(c);
      }
      if (origin != null) {
        origin[out.length()] = i;
      }
      out.append(c);
    }
    return out.toString();
  }
}
