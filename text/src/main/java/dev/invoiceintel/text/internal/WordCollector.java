package dev.invoiceintel.text.internal;

import dev.invoiceintel.text.Word;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * Groups PDFBox {@link TextPosition}s into words: a word ends at whitespace or where PDFBox ends a
 * text run. Boxes use PDFBox's direction-adjusted coordinates, whose origin is the top left of the
 * page.
 */
public final class WordCollector extends PDFTextStripper {

  private final List<Word> words = new ArrayList<>();
  private final List<TextPosition> current = new ArrayList<>();

  public WordCollector() {
    setSortByPosition(true);
  }

  /** The words of one 1-based page. Not thread-safe: one collector per thread. */
  public List<Word> words(PDDocument document, int pageNo) throws IOException {
    words.clear();
    current.clear();
    setStartPage(pageNo);
    setEndPage(pageNo);
    getText(document);
    return List.copyOf(words);
  }

  @Override
  protected void writeString(String text, List<TextPosition> positions) {
    for (TextPosition position : positions) {
      if (position.getUnicode().isBlank()) {
        flush();
      } else {
        current.add(position);
      }
    }
    flush();
  }

  private void flush() {
    if (current.isEmpty()) {
      return;
    }
    var text = new StringBuilder();
    double left = Double.MAX_VALUE;
    double top = Double.MAX_VALUE;
    double right = -Double.MAX_VALUE;
    double bottom = -Double.MAX_VALUE;
    for (TextPosition position : current) {
      text.append(position.getUnicode());
      // getYDirAdj is the baseline measured from the top; the glyph extends up by its height.
      left = Math.min(left, position.getXDirAdj());
      right = Math.max(right, position.getXDirAdj() + position.getWidthDirAdj());
      top = Math.min(top, position.getYDirAdj() - position.getHeightDir());
      bottom = Math.max(bottom, position.getYDirAdj());
    }
    words.add(new Word(text.toString(), left, top, right - left, bottom - top, 1.0));
    current.clear();
  }
}
