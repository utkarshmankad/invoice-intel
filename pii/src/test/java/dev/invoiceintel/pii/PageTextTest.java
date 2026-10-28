package dev.invoiceintel.pii;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.pii.internal.IndianStates;
import dev.invoiceintel.text.TextPage;
import dev.invoiceintel.text.TextSource;
import dev.invoiceintel.text.Word;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class PageTextTest {

  private static final TextPage PAGE =
      new TextPage(
          2,
          100,
          100,
          TextSource.OCR,
          List.of(
              new Word("Phone:", 0, 0, 10, 5, 0.9),
              new Word("+91", 12, 0, 6, 5, 0.9),
              new Word("9876543210", 20, 0, 20, 5, 0.9)));

  @Test
  void text_joinsWordsWithSingleSpaces() {
    PageText text = PageText.of(PAGE);

    assertThat(text.text()).isEqualTo("Phone: +91 9876543210");
    assertThat(text.pageNo()).isEqualTo(2);
  }

  @Test
  void wordsIn_mapsOffsetsBackToWords() {
    PageText text = PageText.of(PAGE);

    assertThat(text.wordsIn(7, 21)).extracting(Word::text).containsExactly("+91", "9876543210");
    assertThat(text.wordsIn(8, 9)).extracting(Word::text).containsExactly("+91");
    assertThat(text.wordsIn(6, 7)).isEmpty();
    assertThat(text.wordsIn(0, 1)).extracting(Word::text).containsExactly("Phone:");
  }

  @Test
  void toString_hidesText() {
    assertThat(PageText.of(PAGE).toString()).isEqualTo("PageText[pageNo=2]").doesNotContain("98");
  }

  @Test
  void emptyPage_hasEmptyText() {
    PageText text = PageText.of(new TextPage(1, 1, 1, TextSource.OCR, List.of()));

    assertThat(text.text()).isEmpty();
    assertThat(text.wordsIn(0, 1)).isEmpty();
  }

  @Test
  void span_rejectsInvalidPositions() {
    assertThatThrownBy(() -> new PiiSpan(0, 0, 1, PiiType.PHONE, "x"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PiiSpan(1, -1, 1, PiiType.PHONE, "x"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PiiSpan(1, 3, 3, PiiType.PHONE, "x"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PiiSpan(1, 0, 1, null, "x"))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new DocumentContext(null)).isInstanceOf(NullPointerException.class);
    assertThat(new DocumentContext(DocumentId.random()).documentId()).isNotNull();
  }

  @Test
  void stateAlternation_prefersLongestName() {
    var m =
        Pattern.compile("(?i:" + IndianStates.alternation() + ")")
            .matcher("Dadra and Nagar Haveli and Daman and Diu");

    assertThat(m.find()).isTrue();
    assertThat(m.group()).isEqualTo("Dadra and Nagar Haveli and Daman and Diu");
    assertThat(IndianStates.NAMES).doesNotHaveDuplicates().hasSizeGreaterThanOrEqualTo(36);
  }
}
