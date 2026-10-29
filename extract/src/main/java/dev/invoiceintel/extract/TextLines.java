package dev.invoiceintel.extract;

import dev.invoiceintel.pii.RedactedContent;
import dev.invoiceintel.text.Word;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Groups a redacted page's words into numbered lines for the {@code TEXT} strategy (EXT-2,
 * architecture.md §6.5). The model cites a line by its marker ({@link Line#ref()}, e.g. {@code
 * p1:l3}) in {@link Evidence#lineRefs()}; evidence mapping (M3.6) uses the same grouping to turn a
 * marker back into word boxes, so the grouping depends only on the word boxes and is deterministic.
 *
 * <p>Words are taken top to bottom; a word joins the current line when its vertical centre lies
 * within the line's first word's box, otherwise it starts a new line. Words in a line are ordered
 * left to right.
 */
public final class TextLines {

  private TextLines() {}

  /**
   * One line of a page.
   *
   * @param page 1-based page number
   * @param line 1-based line number within the page, top to bottom
   * @param words the line's words, left to right
   */
  public record Line(int page, int line, List<Word> words) {

    public Line {
      Objects.requireNonNull(words, "words");
      words = List.copyOf(words);
    }

    /** The marker the model cites: {@code p{page}:l{line}}. */
    public String ref() {
      return TextLines.ref(page, line);
    }

    /** The words joined by single spaces. */
    public String text() {
      return words.stream().map(Word::text).collect(Collectors.joining(" "));
    }

    /** Never prints the words (PII-8). */
    @Override
    public String toString() {
      return "TextLines.Line[" + ref() + ", words=" + words.size() + "]";
    }
  }

  /** The marker for {@code line} on {@code page}: {@code p{page}:l{line}}. */
  public static String ref(int page, int line) {
    return "p" + page + ":l" + line;
  }

  /** The lines of {@code page}, top to bottom. A page without words has no lines. */
  public static List<Line> of(RedactedContent.Page page) {
    Objects.requireNonNull(page, "page");
    List<Word> byTop =
        page.words().stream()
            .sorted(Comparator.comparingDouble(Word::y).thenComparingDouble(Word::x))
            .toList();
    var groups = new ArrayList<List<Word>>();
    Word anchor = null;
    for (Word word : byTop) {
      double centre = word.y() + word.h() / 2;
      if (anchor == null || centre > anchor.y() + anchor.h()) {
        groups.add(new ArrayList<>());
        anchor = word;
      }
      groups.getLast().add(word);
    }
    var lines = new ArrayList<Line>(groups.size());
    for (int i = 0; i < groups.size(); i++) {
      List<Word> words =
          groups.get(i).stream().sorted(Comparator.comparingDouble(Word::x)).toList();
      lines.add(new Line(page.pageNo(), i + 1, words));
    }
    return lines;
  }

  /**
   * The model input of the {@code TEXT} strategy: every line as {@code [p{page}:l{line}] words},
   * one per row, pages in order. It holds only redacted text (PII-1).
   */
  public static String markedText(RedactedContent content) {
    Objects.requireNonNull(content, "content");
    return content.pages().stream()
        .flatMap(page -> of(page).stream())
        .map(line -> "[" + line.ref() + "] " + line.text())
        .collect(Collectors.joining("\n"));
  }
}
