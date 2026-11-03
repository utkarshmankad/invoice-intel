package dev.invoiceintel.eval.metrics.fields;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class LineItemCountsTest {

  @Test
  void precisionRecallF1_fromCounts() {
    var counts = new LineItemCounts(3, 1, 2);

    assertThat(counts.precision()).isCloseTo(0.75, within(1e-9));
    assertThat(counts.recall()).isCloseTo(0.6, within(1e-9));
    assertThat(counts.f1()).isCloseTo(2 * 0.75 * 0.6 / 1.35, within(1e-9));
  }

  @Test
  void nothingLabelledNothingExtracted_isPerfect() {
    assertThat(LineItemCounts.ZERO.precision()).isEqualTo(1.0);
    assertThat(LineItemCounts.ZERO.recall()).isEqualTo(1.0);
    assertThat(LineItemCounts.ZERO.f1()).isEqualTo(1.0);
  }

  @Test
  void nothingExtracted_precisionAndF1Zero() {
    var counts = new LineItemCounts(0, 0, 2);

    assertThat(counts.precision()).isZero();
    assertThat(counts.recall()).isZero();
    assertThat(counts.f1()).isZero();
  }

  @Test
  void nothingLabelled_recallZero() {
    var counts = new LineItemCounts(0, 2, 0);

    assertThat(counts.recall()).isZero();
    assertThat(counts.precision()).isZero();
  }

  @Test
  void plus_sumsCounts() {
    assertThat(new LineItemCounts(1, 2, 3).plus(new LineItemCounts(4, 5, 6)))
        .isEqualTo(new LineItemCounts(5, 7, 9));
  }

  @Test
  void negativeCounts_rejected() {
    assertThatThrownBy(() -> new LineItemCounts(-1, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FieldCounts(2, 1)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void fieldCounts_accuracy() {
    assertThat(FieldCounts.ZERO.accuracy()).isZero();
    assertThat(FieldCounts.ZERO.plus(true).plus(false).accuracy()).isEqualTo(0.5);
  }
}
