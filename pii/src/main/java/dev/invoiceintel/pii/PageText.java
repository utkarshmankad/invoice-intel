package dev.invoiceintel.pii;

import dev.invoiceintel.text.TextPage;
import dev.invoiceintel.text.Word;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The text of one page as its words joined by single spaces, in text-layer order, with the
 * character offset of each word. {@link PiiSpan} offsets refer to this text, and {@link
 * #wordsIn(int, int)} maps them back to words and their boxes for redaction (architecture.md §6.3).
 * It contains PII, so {@link #toString()} shows the page number only.
 */
public final class PageText {

  private final TextPage page;
  private final String text;
  private final int[] wordStarts;

  private PageText(TextPage page) {
    this.page = page;
    List<Word> words = page.words();
    this.wordStarts = new int[words.size()];
    var builder = new StringBuilder();
    for (int i = 0; i < words.size(); i++) {
      if (i > 0) {
        builder.append(' ');
      }
      wordStarts[i] = builder.length();
      builder.append(words.get(i).text());
    }
    this.text = builder.toString();
  }

  public static PageText of(TextPage page) {
    return new PageText(Objects.requireNonNull(page, "page"));
  }

  public int pageNo() {
    return page.pageNo();
  }

  public String text() {
    return text;
  }

  /** Words that overlap characters {@code [start, end)}, in order. */
  public List<Word> wordsIn(int start, int end) {
    List<Word> words = page.words();
    var result = new ArrayList<Word>();
    for (int i = 0; i < words.size(); i++) {
      int wordStart = wordStarts[i];
      int wordEnd = wordStart + words.get(i).text().length();
      if (wordStart < end && wordEnd > start) {
        result.add(words.get(i));
      }
    }
    return result;
  }

  @Override
  public String toString() {
    return "PageText[pageNo=" + page.pageNo() + "]";
  }
}
