package dev.invoiceintel.eval.metrics.fields;

/** How many compared values were correct out of how many were compared. */
public record FieldCounts(int correct, int total) {

  public static final FieldCounts ZERO = new FieldCounts(0, 0);

  public FieldCounts {
    if (correct < 0 || total < correct) {
      throw new IllegalArgumentException("need 0 <= correct <= total");
    }
  }

  public FieldCounts plus(boolean match) {
    return new FieldCounts(correct + (match ? 1 : 0), total + 1);
  }

  public FieldCounts plus(FieldCounts other) {
    return new FieldCounts(correct + other.correct, total + other.total);
  }

  /** correct / total; 0 when nothing was compared. */
  public double accuracy() {
    return total == 0 ? 0.0 : (double) correct / total;
  }
}
