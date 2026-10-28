package dev.invoiceintel.pii;

import dev.invoiceintel.text.TextLayer;
import dev.invoiceintel.text.TextPage;
import dev.invoiceintel.text.TextSource;
import dev.invoiceintel.text.Word;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Hand-made one-page text layers: lines placed at a position, words 8 units high. */
final class Layouts {

  static final double HEIGHT = 8;
  private static final double CHAR_WIDTH = 5;
  private static final double SPACE = 3;

  /** The words of one line. */
  record Line(List<Word> words) {}

  private Layouts() {}

  /** The words of {@code text}, split at spaces, starting at {@code x}, top at {@code y}. */
  static Line line(double x, double y, String text) {
    var words = new ArrayList<Word>();
    double at = x;
    for (String part : text.split(" ")) {
      if (part.isEmpty()) {
        continue;
      }
      double width = part.length() * CHAR_WIDTH;
      words.add(new Word(part, at, y, width, HEIGHT, 1.0));
      at += width + SPACE;
    }
    return new Line(words);
  }

  /** One page whose words are in position order (top to bottom, left to right), like PDF text. */
  static TextLayer page(Line... lines) {
    List<Word> words =
        Arrays.stream(lines)
            .flatMap(line -> line.words().stream())
            .sorted(Comparator.comparingDouble(Word::y).thenComparingDouble(Word::x))
            .toList();
    return new TextLayer(List.of(new TextPage(1, 595, 842, TextSource.PDF_TEXT, words)));
  }
}
