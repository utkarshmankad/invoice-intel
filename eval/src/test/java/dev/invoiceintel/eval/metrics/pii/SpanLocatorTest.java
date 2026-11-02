package dev.invoiceintel.eval.metrics.pii;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Synthetic page text only (testing.md). */
class SpanLocatorTest {

  private static List<PageSpan> locate(String labelText, String page) {
    return SpanLocator.locate(new LabelSpan(PiiSpanType.PERSON, 1, labelText), page);
  }

  @Test
  void locate_exactText_returnsOriginalOffsets() {
    String page = "Bill To Asha Fakename Mumbai";

    assertThat(locate("Asha Fakename", page))
        .containsExactly(new PageSpan(PiiSpanType.PERSON, 1, 8, 21));
  }

  @Test
  void locate_differentCaseAndWhitespace_matchesAndKeepsOriginalLength() {
    String page = "Bill To ASHA \n  FAKENAME, Mumbai";

    List<PageSpan> spans = locate("  asha   fakename ", page);

    assertThat(spans).containsExactly(new PageSpan(PiiSpanType.PERSON, 1, 8, 24));
    assertThat(page.substring(8, 24)).isEqualTo("ASHA \n  FAKENAME");
  }

  @Test
  void locate_repeatedText_returnsEveryOccurrence() {
    String page = "Ship To Asha Fakename Bill To asha fakename";

    assertThat(locate("Asha Fakename", page)).extracting(PageSpan::start).containsExactly(8, 30);
  }

  @Test
  void locate_textInsideLongerWord_isNotMatched() {
    assertThat(locate("Ravi", "Ravindra Fakename")).isEmpty();
    assertThat(locate("Ravi", "Shravi Fakename")).isEmpty();
  }

  @Test
  void locate_wordInsideWordThenWholeWord_matchesWholeWordOnly() {
    assertThat(locate("Ravi", "Ravindra and Ravi"))
        .containsExactly(new PageSpan(PiiSpanType.PERSON, 1, 13, 17));
  }

  @Test
  void locate_textEdgedByPunctuation_needsNoWordBoundary() {
    assertThat(locate("-123", "ref9-123 x")).hasSize(1);
  }

  @Test
  void locate_missingText_returnsEmpty() {
    assertThat(locate("Asha Fakename", "Sold By Example Traders")).isEmpty();
  }

  @Test
  void locate_blankText_returnsEmpty() {
    assertThat(locate(" \t ", "any text")).isEmpty();
  }

  @Test
  void locate_keepsLabelTypeAndPage() {
    LabelSpan label = new LabelSpan(PiiSpanType.PIN_CODE, 2, "400099");

    assertThat(SpanLocator.locate(label, "Mumbai 400099"))
        .containsExactly(new PageSpan(PiiSpanType.PIN_CODE, 2, 7, 13));
  }
}
