package dev.invoiceintel.eval.metrics.fields;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.invoiceintel.eval.metrics.fields.DocumentValues.LineItem;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class LineItemMatcherTest {

  private static LineItem item(String description, String total) {
    return new LineItem(description, total == null ? null : new BigDecimal(total));
  }

  @Test
  void match_identicalItems_allTruePositives() {
    List<LineItem> items = List.of(item("Steel water bottle", "499.00"), item("Shipping", "40.00"));

    assertThat(LineItemMatcher.match(items, items)).isEqualTo(new LineItemCounts(2, 0, 0));
  }

  @Test
  void match_similarDescriptionAndTotalWithinOnePaisa_matches() {
    var expected = List.of(item("Steel Water Bottle 1L, Blue", "499.00"));
    var actual = List.of(item("steel water bottle 1L", "499.01"));

    assertThat(LineItemMatcher.match(expected, actual)).isEqualTo(new LineItemCounts(1, 0, 0));
  }

  @Test
  void match_totalOffByMoreThanOnePaisa_doesNotMatch() {
    var expected = List.of(item("Steel water bottle", "499.00"));
    var actual = List.of(item("Steel water bottle", "499.02"));

    assertThat(LineItemMatcher.match(expected, actual)).isEqualTo(new LineItemCounts(0, 1, 1));
  }

  @Test
  void match_dissimilarDescriptionSameTotal_doesNotMatch() {
    var expected = List.of(item("Steel water bottle", "499.00"));
    var actual = List.of(item("Paperback novel", "499.00"));

    assertThat(LineItemMatcher.match(expected, actual)).isEqualTo(new LineItemCounts(0, 1, 1));
  }

  @Test
  void match_extraAndMissingItems_countedAsFalsePositivesAndNegatives() {
    var expected = List.of(item("Cotton bedsheet", "799.00"), item("Pillow cover set", "299.00"));
    var actual =
        List.of(
            item("Cotton bedsheet", "799.00"),
            item("Convenience fee", "10.00"),
            item("Shipping charges", "40.00"));

    assertThat(LineItemMatcher.match(expected, actual)).isEqualTo(new LineItemCounts(1, 2, 1));
  }

  @Test
  void match_eachItemUsedOnce_duplicatesDoNotDoubleCount() {
    var expected = List.of(item("USB cable", "199.00"));
    var actual = List.of(item("USB cable", "199.00"), item("USB cable", "199.00"));

    assertThat(LineItemMatcher.match(expected, actual)).isEqualTo(new LineItemCounts(1, 1, 0));
  }

  @Test
  void match_greedyPrefersMostSimilarPair() {
    // "USB C cable" fits both labels by amount; the exact description wins its own label,
    // leaving the second extraction for the other label.
    var expected = List.of(item("USB cable", "199.00"), item("USB C cable", "199.00"));
    var actual = List.of(item("USB cable 1m", "199.00"), item("USB C cable", "199.00"));

    assertThat(LineItemMatcher.match(expected, actual)).isEqualTo(new LineItemCounts(2, 0, 0));
  }

  @Test
  void match_missingTotalOrDescription_onlyMatchesWhenBothMissing() {
    assertThat(
            LineItemMatcher.match(
                List.of(item("Gift wrap", null)), List.of(item("Gift wrap", "25.00"))))
        .isEqualTo(new LineItemCounts(0, 1, 1));
    assertThat(
            LineItemMatcher.match(
                List.of(item("Gift wrap", null)), List.of(item("Gift wrap", null))))
        .isEqualTo(new LineItemCounts(1, 0, 0));
    assertThat(LineItemMatcher.match(List.of(item(null, "25.00")), List.of(item(null, "25.00"))))
        .isEqualTo(new LineItemCounts(0, 1, 1));
  }

  @Test
  void similarity_tokenJaccardIgnoringCaseAndPunctuation() {
    assertThat(LineItemMatcher.similarity("Steel Bottle, 1L", "steel bottle 1l")).isEqualTo(1.0);
    assertThat(LineItemMatcher.similarity("a b c d", "a b")).isCloseTo(0.5, within(1e-9));
    assertThat(LineItemMatcher.similarity("---", "")).isEqualTo(1.0);
    assertThat(LineItemMatcher.similarity("abc", "")).isZero();
    assertThat(LineItemMatcher.similarity(null, "abc")).isZero();
  }

  @Test
  void match_emptyLists_noCounts() {
    assertThat(LineItemMatcher.match(List.of(), List.of())).isEqualTo(LineItemCounts.ZERO);
  }
}
