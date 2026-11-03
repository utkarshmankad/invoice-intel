package dev.invoiceintel.eval.metrics.pii;

import java.util.OptionalDouble;

/**
 * Span counts behind PII recall and precision (architecture.md §6.9). Counts only, so it is safe to
 * log and report (PII-8).
 *
 * @param expected expected PII: every located label occurrence plus every unlocated label span
 * @param found expected occurrences covered ≥ 50% by detected spans of any type
 * @param foundSameType of {@code found}, those covered ≥ 50% by detected spans of their own type
 * @param unlocated label spans whose text wasn't found on their page; counted as expected and
 *     missed (recall bias, specs §6.3)
 * @param detected detected spans
 * @param correct detected spans covered ≥ 50% by expected occurrences of any type
 */
public record PiiCounts(
    long expected, long found, long foundSameType, long unlocated, long detected, long correct) {

  public static final PiiCounts ZERO = new PiiCounts(0, 0, 0, 0, 0, 0);

  public PiiCounts {
    if (expected < 0
        || found < 0
        || foundSameType < 0
        || unlocated < 0
        || detected < 0
        || correct < 0
        || found + unlocated > expected
        || foundSameType > found
        || correct > detected) {
      throw new IllegalArgumentException("inconsistent PII counts");
    }
  }

  public PiiCounts plus(PiiCounts other) {
    return new PiiCounts(
        expected + other.expected,
        found + other.found,
        foundSameType + other.foundSameType,
        unlocated + other.unlocated,
        detected + other.detected,
        correct + other.correct);
  }

  /** {@code found / expected}; empty when nothing was expected. */
  public OptionalDouble recall() {
    return ratio(found, expected);
  }

  /** {@code foundSameType / expected}: recall when the detected type must match too. */
  public OptionalDouble typedRecall() {
    return ratio(foundSameType, expected);
  }

  /** {@code correct / detected}; empty when nothing was detected. */
  public OptionalDouble precision() {
    return ratio(correct, detected);
  }

  private static OptionalDouble ratio(long part, long whole) {
    return whole == 0 ? OptionalDouble.empty() : OptionalDouble.of((double) part / whole);
  }
}
