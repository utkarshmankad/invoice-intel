package dev.invoiceintel.extract;

import static org.assertj.core.api.Assertions.assertThat;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.pii.RedactedContent;
import dev.invoiceintel.pii.RedactedContentFixtures;
import dev.invoiceintel.text.Word;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Line grouping and markers of the {@code TEXT} strategy (M3.4). Synthetic text only. */
class TextLinesTest {

  private final DocumentId documentId = DocumentId.random();

  private static Word word(String text, double x, double y) {
    return new Word(text, x, y, text.length() * 6, 12, 1.0);
  }

  @Test
  void markedText_fixturePages_numbersLinesPerPage() {
    RedactedContent content =
        RedactedContentFixtures.of(
            documentId, List.of("Tax Invoice\nInvoice No INV-TOKEN-1", "Grand Total 1180.00"));

    assertThat(TextLines.markedText(content))
        .isEqualTo(
            """
            [p1:l1] Tax Invoice
            [p1:l2] Invoice No INV-TOKEN-1
            [p2:l1] Grand Total 1180.00""");
  }

  @Test
  void of_wordsOutOfOrderWithJitter_groupsByRowAndSortsLeftToRight() {
    // OCR order is not reading order, and words on one row sit a few units apart vertically.
    RedactedContent content =
        RedactedContentFixtures.ofWords(
            documentId,
            List.of(
                List.of(
                    word("1180.00", 300, 62),
                    word("Seller", 40, 20),
                    word("Total", 40, 60),
                    word("Example", 100, 23),
                    word("Grand", 0, 58))));

    List<TextLines.Line> lines = TextLines.of(content.pages().getFirst());

    assertThat(lines).extracting(TextLines.Line::ref).containsExactly("p1:l1", "p1:l2");
    assertThat(lines)
        .extracting(TextLines.Line::text)
        .containsExactly("Seller Example", "Grand Total 1180.00");
  }

  @Test
  void of_wordBelowLineBox_startsNewLine() {
    RedactedContent content =
        RedactedContentFixtures.ofWords(
            documentId, List.of(List.of(word("upper", 40, 20), word("lower", 40, 27))));

    // "lower" is centred at 33, below "upper"'s box (20–32).
    assertThat(TextLines.of(content.pages().getFirst()))
        .extracting(TextLines.Line::text)
        .containsExactly("upper", "lower");
  }

  @Test
  void of_pageWithoutWords_hasNoLines() {
    RedactedContent content = RedactedContentFixtures.ofWords(documentId, List.of(List.of()));

    assertThat(TextLines.of(content.pages().getFirst())).isEmpty();
    assertThat(TextLines.markedText(content)).isEmpty();
  }

  @Test
  void line_toString_hidesWords() {
    var line = new TextLines.Line(2, 5, List.of(word("INV-TOKEN-1", 0, 0)));

    assertThat(line.toString()).isEqualTo("TextLines.Line[p2:l5, words=1]").doesNotContain("INV");
  }
}
