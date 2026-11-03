package dev.invoiceintel.eval.metrics.fields;

import dev.invoiceintel.eval.metrics.fields.DocumentValues.LineItem;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One-to-one matching of extracted line items to labelled ones for the line-item F1 (EVAL-2,
 * architecture §6.9). A pair is a candidate when the descriptions' token Jaccard similarity is at
 * least {@link #MIN_DESCRIPTION_SIMILARITY} and the line totals differ by at most {@link
 * #LINE_TOTAL_TOLERANCE}. Candidates are taken greedily, most similar first (ties: label order,
 * then extraction order), each item used at most once.
 */
public final class LineItemMatcher {

  public static final double MIN_DESCRIPTION_SIMILARITY = 0.5;
  public static final BigDecimal LINE_TOTAL_TOLERANCE = new BigDecimal("0.01");

  private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

  private LineItemMatcher() {}

  public static LineItemCounts match(List<LineItem> expected, List<LineItem> actual) {
    record Candidate(int expected, int actual, double similarity) {}

    List<Candidate> candidates = new ArrayList<>();
    for (int i = 0; i < expected.size(); i++) {
      for (int j = 0; j < actual.size(); j++) {
        LineItem e = expected.get(i);
        LineItem a = actual.get(j);
        double similarity = similarity(e.description(), a.description());
        if (similarity >= MIN_DESCRIPTION_SIMILARITY && totalsMatch(e.lineTotal(), a.lineTotal())) {
          candidates.add(new Candidate(i, j, similarity));
        }
      }
    }
    candidates.sort(
        Comparator.comparingDouble(Candidate::similarity)
            .reversed()
            .thenComparingInt(Candidate::expected)
            .thenComparingInt(Candidate::actual));

    Set<Integer> usedExpected = new HashSet<>();
    Set<Integer> usedActual = new HashSet<>();
    for (Candidate c : candidates) {
      if (!usedExpected.contains(c.expected()) && !usedActual.contains(c.actual())) {
        usedExpected.add(c.expected());
        usedActual.add(c.actual());
      }
    }
    int matched = usedExpected.size();
    return new LineItemCounts(matched, actual.size() - matched, expected.size() - matched);
  }

  /**
   * Jaccard similarity of the descriptions' word sets (letters and digits, case ignored). Two
   * descriptions without any words are similar (1); a missing description matches nothing (0).
   */
  public static double similarity(String a, String b) {
    if (a == null || b == null) {
      return 0.0;
    }
    Set<String> left = words(a);
    Set<String> right = words(b);
    if (left.isEmpty() && right.isEmpty()) {
      return 1.0;
    }
    Set<String> union = new HashSet<>(left);
    union.addAll(right);
    Set<String> intersection = new HashSet<>(left);
    intersection.retainAll(right);
    return (double) intersection.size() / union.size();
  }

  private static Set<String> words(String text) {
    Set<String> words = new HashSet<>();
    Arrays.stream(NON_WORD.split(text.toLowerCase(Locale.ROOT)))
        .filter(w -> !w.isEmpty())
        .forEach(words::add);
    return words;
  }

  private static boolean totalsMatch(BigDecimal e, BigDecimal a) {
    if (e == null || a == null) {
      return e == null && a == null;
    }
    return e.subtract(a).abs().compareTo(LINE_TOTAL_TOLERANCE) <= 0;
  }
}
