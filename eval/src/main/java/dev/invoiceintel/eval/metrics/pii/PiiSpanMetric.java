package dev.invoiceintel.eval.metrics.pii;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Scores one document's detected PII spans against its label spans (EVAL-2, specs §6.3,
 * architecture.md §6.9).
 *
 * <p>Overlap is character coverage on the same page, measured on each span's own length, with the
 * other side's spans taken as a union (a merged address may come back as ADDRESS, PIN_CODE, ADDRESS
 * pieces):
 *
 * <ul>
 *   <li>an expected occurrence is <b>found</b> when detected spans cover ≥ 50% of its characters;
 *   <li>a detected span is <b>correct</b> when expected occurrences cover ≥ 50% of its characters.
 * </ul>
 *
 * Types don't have to match: recall measures whether PII is removed and precision whether non-PII
 * is (specs §6.3), and a span of the wrong type is removed all the same. Typed recall (coverage by
 * detected spans of the expected type only) is counted alongside.
 */
public final class PiiSpanMetric {

  private PiiSpanMetric() {}

  /**
   * Scores one document.
   *
   * @param pageTexts the Text stage's page text by 1-based page number
   * @param labels the label file's {@code piiSpans}
   * @param detected the PII stage's spans (offsets into {@code pageTexts})
   * @throws IllegalArgumentException when a detected span lies outside its page's text (the message
   *     holds the page number only)
   */
  public static PiiScore score(
      Map<Integer, String> pageTexts, List<LabelSpan> labels, List<PageSpan> detected) {
    Map<PiiSpanType, Tally> tallies = new EnumMap<>(PiiSpanType.class);
    for (PiiSpanType type : PiiSpanType.values()) {
      tallies.put(type, new Tally());
    }

    List<PageSpan> expected = new ArrayList<>();
    for (LabelSpan label : labels) {
      String text = pageTexts.get(label.page());
      List<PageSpan> occurrences = text == null ? List.of() : SpanLocator.locate(label, text);
      if (occurrences.isEmpty()) {
        Tally t = tallies.get(label.type());
        t.expected++;
        t.unlocated++;
      }
      expected.addAll(occurrences);
    }

    Map<Integer, BitSet> detectedAny = new HashMap<>();
    Map<Integer, Map<PiiSpanType, BitSet>> detectedByType = new HashMap<>();
    for (PageSpan span : detected) {
      String text = pageTexts.get(span.page());
      if (text == null || span.end() > text.length()) {
        throw new IllegalArgumentException("detected span outside the text of page " + span.page());
      }
      detectedAny.computeIfAbsent(span.page(), p -> new BitSet()).set(span.start(), span.end());
      detectedByType
          .computeIfAbsent(span.page(), p -> new EnumMap<>(PiiSpanType.class))
          .computeIfAbsent(span.type(), t -> new BitSet())
          .set(span.start(), span.end());
    }

    Map<Integer, BitSet> expectedAny = new HashMap<>();
    for (PageSpan occurrence : expected) {
      expectedAny
          .computeIfAbsent(occurrence.page(), p -> new BitSet())
          .set(occurrence.start(), occurrence.end());
      Tally t = tallies.get(occurrence.type());
      t.expected++;
      if (halfCovered(occurrence, detectedAny.get(occurrence.page()))) {
        t.found++;
        BitSet sameType =
            detectedByType.getOrDefault(occurrence.page(), Map.of()).get(occurrence.type());
        if (halfCovered(occurrence, sameType)) {
          t.foundSameType++;
        }
      }
    }

    for (PageSpan span : detected) {
      Tally t = tallies.get(span.type());
      t.detected++;
      if (halfCovered(span, expectedAny.get(span.page()))) {
        t.correct++;
      }
    }

    Map<PiiSpanType, PiiCounts> byType = new EnumMap<>(PiiSpanType.class);
    PiiCounts total = PiiCounts.ZERO;
    for (Map.Entry<PiiSpanType, Tally> e : tallies.entrySet()) {
      PiiCounts counts = e.getValue().toCounts();
      byType.put(e.getKey(), counts);
      total = total.plus(counts);
    }
    return new PiiScore(total, byType);
  }

  /** True when {@code cover} covers at least half of {@code span}'s characters. */
  private static boolean halfCovered(PageSpan span, BitSet cover) {
    if (cover == null) {
      return false;
    }
    int covered = cover.get(span.start(), span.end()).cardinality();
    return 2L * covered >= span.length();
  }

  private static final class Tally {
    long expected;
    long found;
    long foundSameType;
    long unlocated;
    long detected;
    long correct;

    PiiCounts toCounts() {
      return new PiiCounts(expected, found, foundSameType, unlocated, detected, correct);
    }
  }
}
