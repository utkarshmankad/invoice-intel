package dev.invoiceintel.eval.metrics.pii;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PiiScoreTest {

  @Test
  void plus_sumsTotalsAndEveryType() {
    PiiCounts a = new PiiCounts(4, 3, 2, 1, 5, 4);
    PiiCounts b = new PiiCounts(2, 2, 2, 0, 1, 1);
    PiiScore first = new PiiScore(a, Map.of(PiiSpanType.PERSON, a));
    PiiScore second = new PiiScore(b, Map.of(PiiSpanType.PERSON, b, PiiSpanType.EMAIL, b));

    PiiScore sum = PiiScore.EMPTY.plus(first).plus(second);

    assertThat(sum.total()).isEqualTo(new PiiCounts(6, 5, 4, 1, 6, 5));
    assertThat(sum.of(PiiSpanType.PERSON)).isEqualTo(new PiiCounts(6, 5, 4, 1, 6, 5));
    assertThat(sum.of(PiiSpanType.EMAIL)).isEqualTo(b);
    assertThat(sum.of(PiiSpanType.GSTIN)).isEqualTo(PiiCounts.ZERO);
    assertThat(sum.byType()).hasSize(PiiSpanType.values().length);
  }

  @Test
  void ratios_divideCounts() {
    PiiCounts counts = new PiiCounts(4, 3, 2, 1, 5, 4);

    assertThat(counts.recall()).hasValue(0.75);
    assertThat(counts.typedRecall()).hasValue(0.5);
    assertThat(counts.precision()).hasValue(0.8);
  }

  @ParameterizedTest
  @CsvSource({
    "-1,0,0,0,0,0",
    "0,-1,0,0,0,0",
    "0,0,-1,0,0,0",
    "0,0,0,-1,0,0",
    "0,0,0,0,-1,0",
    "0,0,0,0,0,-1",
    "1,1,0,1,0,0",
    "1,0,1,0,0,0",
    "0,0,0,0,1,2"
  })
  void counts_inconsistent_areRejected(
      long expected, long found, long sameType, long unlocated, long detected, long correct) {
    assertThatThrownBy(() -> new PiiCounts(expected, found, sameType, unlocated, detected, correct))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void labelSpan_toString_leavesTextOut() {
    LabelSpan span = new LabelSpan(PiiSpanType.PERSON, 1, "Asha Fakename");

    assertThat(span.toString()).doesNotContain("Asha").contains("PERSON").contains("length=13");
  }

  @Test
  void labelSpan_pageBelowOne_isRejected() {
    assertThatThrownBy(() -> new LabelSpan(PiiSpanType.PERSON, 0, "x"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @CsvSource({"0,0,1", "1,-1,1", "1,2,2", "1,3,2"})
  void pageSpan_invalidRange_isRejected(int page, int start, int end) {
    assertThatThrownBy(() -> new PageSpan(PiiSpanType.PERSON, page, start, end))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
