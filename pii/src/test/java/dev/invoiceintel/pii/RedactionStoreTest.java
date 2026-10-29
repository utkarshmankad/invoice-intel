package dev.invoiceintel.pii;

import static org.assertj.core.api.Assertions.assertThat;

import dev.invoiceintel.pii.internal.PiiSpanRepository;
import dev.invoiceintel.text.Word;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The span box of {@link RedactionStore}; storing is covered by the app's {@code PiiStageIT}. */
class RedactionStoreTest {

  @Test
  void box_wordsOnTwoLines_isTheirUnion() {
    List<Word> words =
        List.of(new Word("Flat", 40, 100, 20, 10, 1), new Word("Road", 30, 115, 25, 12, 1));

    assertThat(RedactionStore.box(words)).isEqualTo(new PiiSpanRepository.Box(30, 100, 30, 27));
  }

  @Test
  void box_oneWord_isItsBox() {
    assertThat(RedactionStore.box(List.of(new Word("Rohan", 5, 6, 7, 8, 0.9))))
        .isEqualTo(new PiiSpanRepository.Box(5, 6, 7, 8));
  }
}
