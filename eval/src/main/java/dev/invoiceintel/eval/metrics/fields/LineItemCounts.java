package dev.invoiceintel.eval.metrics.fields;

/**
 * Line-item matching counts: matched pairs (true positives), extracted items without a labelled
 * match (false positives) and labelled items without an extracted match (false negatives).
 */
public record LineItemCounts(int truePositives, int falsePositives, int falseNegatives) {

  public static final LineItemCounts ZERO = new LineItemCounts(0, 0, 0);

  public LineItemCounts {
    if (truePositives < 0 || falsePositives < 0 || falseNegatives < 0) {
      throw new IllegalArgumentException("counts must not be negative");
    }
  }

  public LineItemCounts plus(LineItemCounts other) {
    return new LineItemCounts(
        truePositives + other.truePositives,
        falsePositives + other.falsePositives,
        falseNegatives + other.falseNegatives);
  }

  /** TP / (TP + FP); 1 when nothing was extracted and nothing was missed, else 0 if no items. */
  public double precision() {
    int predicted = truePositives + falsePositives;
    if (predicted == 0) {
      return falseNegatives == 0 ? 1.0 : 0.0;
    }
    return (double) truePositives / predicted;
  }

  /** TP / (TP + FN); 1 when nothing was labelled and nothing was extracted, else 0 if no labels. */
  public double recall() {
    int labelled = truePositives + falseNegatives;
    if (labelled == 0) {
      return falsePositives == 0 ? 1.0 : 0.0;
    }
    return (double) truePositives / labelled;
  }

  /** Harmonic mean of precision and recall; 0 when both are 0. */
  public double f1() {
    double p = precision();
    double r = recall();
    return p + r == 0 ? 0.0 : 2 * p * r / (p + r);
  }
}
