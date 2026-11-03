package dev.invoiceintel.eval.metrics.pii;

import static dev.invoiceintel.eval.metrics.pii.PiiSpanType.ADDRESS;
import static dev.invoiceintel.eval.metrics.pii.PiiSpanType.GSTIN;
import static dev.invoiceintel.eval.metrics.pii.PiiSpanType.PERSON;
import static dev.invoiceintel.eval.metrics.pii.PiiSpanType.PHONE;
import static dev.invoiceintel.eval.metrics.pii.PiiSpanType.PIN_CODE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Synthetic page text and spans only (testing.md). */
class PiiSpanMetricTest {

  private static final String PAGE1 =
      "Bill To Asha Fakename 12 Test Lane Pune 411099 Ph 9000000001";
  private static final Map<Integer, String> PAGES = Map.of(1, PAGE1, 2, "Order 1 page two");

  private static final LabelSpan NAME = new LabelSpan(PERSON, 1, "asha fakename");
  private static final LabelSpan STREET = new LabelSpan(ADDRESS, 1, "12 Test Lane Pune");
  private static final LabelSpan PIN = new LabelSpan(PIN_CODE, 1, "411099");

  private static PageSpan span(PiiSpanType type, int start, int end) {
    return new PageSpan(type, 1, start, end);
  }

  private static int at(String text) {
    return PAGE1.indexOf(text);
  }

  private static PageSpan exact(PiiSpanType type, String text) {
    return span(type, at(text), at(text) + text.length());
  }

  @Test
  void score_exactDetections_givesFullRecallAndPrecision() {
    PiiScore score =
        PiiSpanMetric.score(
            PAGES,
            List.of(NAME, STREET, PIN),
            List.of(
                exact(PERSON, "Asha Fakename"),
                exact(ADDRESS, "12 Test Lane Pune"),
                exact(PIN_CODE, "411099")));

    assertThat(score.total()).isEqualTo(new PiiCounts(3, 3, 3, 0, 3, 3));
    assertThat(score.total().recall()).hasValue(1.0);
    assertThat(score.total().precision()).hasValue(1.0);
  }

  @Test
  void score_detectionCoveringExactlyHalf_counts() {
    // "Asha Fakename" is 13 chars; "Asha Fa" covers 7 (>= 6.5) -> found.
    PiiScore score = PiiSpanMetric.score(PAGES, List.of(NAME), List.of(span(PERSON, 8, 15)));

    assertThat(score.total().found()).isEqualTo(1);
  }

  @Test
  void score_detectionCoveringLessThanHalf_missesAndIsWrong() {
    // "Asha " is 5 of 13 characters.
    PiiScore score = PiiSpanMetric.score(PAGES, List.of(NAME), List.of(span(PERSON, 8, 13)));

    assertThat(score.total()).isEqualTo(new PiiCounts(1, 0, 0, 0, 1, 1));
    assertThat(score.total().recall()).hasValue(0.0);
  }

  @Test
  void score_wideDetectionOverShortPii_findsItButIsNotCorrect() {
    // Detected span covers the whole page; the PIN is found, but most of the span isn't PII.
    PiiScore score =
        PiiSpanMetric.score(PAGES, List.of(PIN), List.of(span(ADDRESS, 0, PAGE1.length())));

    assertThat(score.total()).isEqualTo(new PiiCounts(1, 1, 0, 0, 1, 0));
    assertThat(score.total().precision()).hasValue(0.0);
  }

  @Test
  void score_splitDetections_countTheirUnionForRecall() {
    // The street comes back as two pieces, each under half of it.
    PiiScore score =
        PiiSpanMetric.score(
            PAGES,
            List.of(STREET),
            List.of(exact(ADDRESS, "12 Test"), exact(ADDRESS, "Lane Pune")));

    assertThat(score.total()).isEqualTo(new PiiCounts(1, 1, 1, 0, 2, 2));
  }

  @Test
  void score_typeMismatch_countsForRecallAndPrecisionButNotTypedRecall() {
    PiiScore score =
        PiiSpanMetric.score(PAGES, List.of(NAME), List.of(exact(ADDRESS, "Asha Fakename")));

    assertThat(score.total()).isEqualTo(new PiiCounts(1, 1, 0, 0, 1, 1));
    assertThat(score.total().typedRecall()).hasValue(0.0);
    assertThat(score.of(PERSON)).isEqualTo(new PiiCounts(1, 1, 0, 0, 0, 0));
    assertThat(score.of(ADDRESS)).isEqualTo(new PiiCounts(0, 0, 0, 0, 1, 1));
  }

  @Test
  void score_unlabelledDetection_lowersPrecision() {
    PiiScore score =
        PiiSpanMetric.score(
            PAGES, List.of(NAME), List.of(exact(PERSON, "Asha Fakename"), exact(PHONE, "Ph")));

    assertThat(score.total().precision()).hasValue(0.5);
    assertThat(score.of(PHONE)).isEqualTo(new PiiCounts(0, 0, 0, 0, 1, 0));
  }

  @Test
  void score_labelTextNotOnPage_isUnlocatedAndMissed() {
    PiiScore score =
        PiiSpanMetric.score(
            PAGES,
            List.of(NAME, new LabelSpan(GSTIN, 1, "27AAAAA0000A1Z5")),
            List.of(exact(PERSON, "Asha Fakename")));

    assertThat(score.total()).isEqualTo(new PiiCounts(2, 1, 1, 1, 1, 1));
    assertThat(score.of(GSTIN)).isEqualTo(new PiiCounts(1, 0, 0, 1, 0, 0));
    assertThat(score.total().recall()).hasValue(0.5);
  }

  @Test
  void score_labelOnMissingPage_isUnlocated() {
    PiiScore score =
        PiiSpanMetric.score(PAGES, List.of(new LabelSpan(PERSON, 3, "Asha")), List.of());

    assertThat(score.total()).isEqualTo(new PiiCounts(1, 0, 0, 1, 0, 0));
  }

  @Test
  void score_repeatedLabelText_expectsEveryOccurrence() {
    Map<Integer, String> pages = Map.of(1, "Ship To Asha Fakename Bill To Asha Fakename");

    PiiScore score =
        PiiSpanMetric.score(pages, List.of(NAME), List.of(new PageSpan(PERSON, 1, 8, 21)));

    assertThat(score.total()).isEqualTo(new PiiCounts(2, 1, 1, 0, 1, 1));
  }

  @Test
  void score_detectionOnOtherPage_doesNotMatch() {
    Map<Integer, String> pages = Map.of(1, "Asha Fakename", 2, "Asha Fakename");

    PiiScore score =
        PiiSpanMetric.score(
            pages,
            List.of(new LabelSpan(PERSON, 1, "Asha Fakename")),
            List.of(new PageSpan(PERSON, 2, 0, 13)));

    assertThat(score.total()).isEqualTo(new PiiCounts(1, 0, 0, 0, 1, 0));
  }

  @Test
  void score_nothingExpectedOrDetected_hasNoRatios() {
    PiiScore score = PiiSpanMetric.score(PAGES, List.of(), List.of());

    assertThat(score.total()).isEqualTo(PiiCounts.ZERO);
    assertThat(score.total().recall()).isEmpty();
    assertThat(score.total().precision()).isEmpty();
  }

  @Test
  void score_detectionBeyondPageText_isRejectedWithoutText() {
    assertThatThrownBy(
            () -> PiiSpanMetric.score(PAGES, List.of(), List.of(new PageSpan(PERSON, 2, 0, 500))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("detected span outside the text of page 2");
  }

  @Test
  void score_detectionOnUnknownPage_isRejected() {
    assertThatThrownBy(
            () -> PiiSpanMetric.score(PAGES, List.of(), List.of(new PageSpan(PERSON, 9, 0, 1))))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
