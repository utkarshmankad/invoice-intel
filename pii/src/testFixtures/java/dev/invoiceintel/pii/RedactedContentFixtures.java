package dev.invoiceintel.pii;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.text.Word;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds {@link RedactedContent} for other modules' tests (architecture.md §6.4). Test fixtures
 * only: main code still gets a {@code RedactedContent} only from the PII stage. Callers pass
 * synthetic, already pseudonymized text (testing.md).
 */
public final class RedactedContentFixtures {

  private static final double PAGE_WIDTH = 595;
  private static final double PAGE_HEIGHT = 842;
  private static final double LINE_HEIGHT = 14;
  private static final double CHAR_WIDTH = 6;

  private RedactedContentFixtures() {}

  /**
   * One page per entry of {@code pageTexts}; each page's words are its text split on whitespace,
   * laid out left to right with one line per {@code \n}.
   */
  public static RedactedContent of(DocumentId documentId, List<String> pageTexts) {
    var pages = new ArrayList<RedactedContent.Page>();
    for (int i = 0; i < pageTexts.size(); i++) {
      pages.add(page(i + 1, pageTexts.get(i)));
    }
    return new RedactedContent(documentId, RedactionMode.PSEUDONYMIZE, pages);
  }

  /** One page per entry of {@code pageWords}, with exactly those words and boxes. */
  public static RedactedContent ofWords(DocumentId documentId, List<List<Word>> pageWords) {
    var pages = new ArrayList<RedactedContent.Page>();
    for (int i = 0; i < pageWords.size(); i++) {
      pages.add(new RedactedContent.Page(i + 1, PAGE_WIDTH, PAGE_HEIGHT, pageWords.get(i)));
    }
    return new RedactedContent(documentId, RedactionMode.PSEUDONYMIZE, pages);
  }

  /**
   * {@code content} with {@code pngs.get(i)} as page {@code i + 1}'s masked image, as if {@link
   * ImageRedactor} ran; a {@code null} entry leaves that page without one. Pass synthetic images.
   */
  public static RedactedContent withImages(RedactedContent content, List<byte[]> pngs) {
    if (pngs.size() != content.pages().size()) {
      throw new IllegalArgumentException("one image entry per page expected");
    }
    var pages = new ArrayList<RedactedContent.Page>();
    for (int i = 0; i < pngs.size(); i++) {
      RedactedContent.Page page = content.pages().get(i);
      pages.add(pngs.get(i) == null ? page : page.withImage(pngs.get(i)));
    }
    return content.withPages(pages);
  }

  private static RedactedContent.Page page(int pageNo, String text) {
    var words = new ArrayList<Word>();
    String[] lines = text.split("\n", -1);
    for (int line = 0; line < lines.length; line++) {
      double x = 40;
      double y = 40 + line * LINE_HEIGHT;
      for (String token : lines[line].trim().split("\\s+")) {
        if (token.isEmpty()) {
          continue;
        }
        double w = token.length() * CHAR_WIDTH;
        words.add(new Word(token, x, y, w, LINE_HEIGHT - 2, 1.0));
        x += w + CHAR_WIDTH;
      }
    }
    return new RedactedContent.Page(pageNo, PAGE_WIDTH, PAGE_HEIGHT, words);
  }
}
