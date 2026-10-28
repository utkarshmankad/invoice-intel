package dev.invoiceintel.text;

import java.util.List;
import java.util.Objects;

/**
 * One page of a {@link TextLayer}. {@code width}, {@code height} and the word boxes share one unit
 * per page: PDF points (1/72 inch) for {@link TextSource#PDF_TEXT}, with the origin at the top left
 * of the page.
 *
 * @param pageNo 1-based page number
 */
public record TextPage(
    int pageNo, double width, double height, TextSource source, List<Word> words) {

  public TextPage {
    if (pageNo < 1) {
      throw new IllegalArgumentException("pageNo must be >= 1");
    }
    Objects.requireNonNull(source, "source");
    words = List.copyOf(Objects.requireNonNull(words, "words"));
  }

  /** Number of non-whitespace characters on the page. */
  public int textLength() {
    return words.stream().mapToInt(word -> word.text().length()).sum();
  }
}
