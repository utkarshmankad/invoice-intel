package dev.invoiceintel.pii;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** Turns word indices of a page into character ranges of its {@link PageText}. */
final class WordRuns {

  private WordRuns() {}

  /**
   * One range per run of words that are adjacent in text-layer order. Words of one line are
   * adjacent, but the lines of a block are not when columns interleave.
   */
  static List<TextRange> ranges(PageText text, List<Integer> words) {
    var ranges = new ArrayList<TextRange>();
    int first = -1;
    int last = -1;
    for (int index : new TreeSet<>(words)) {
      if (first >= 0 && index != last + 1) {
        ranges.add(range(text, first, last));
        first = -1;
      }
      if (first < 0) {
        first = index;
      }
      last = index;
    }
    if (first >= 0) {
      ranges.add(range(text, first, last));
    }
    return ranges;
  }

  private static TextRange range(PageText text, int first, int last) {
    return new TextRange(text.pageNo(), text.wordStart(first), text.wordEnd(last));
  }
}
