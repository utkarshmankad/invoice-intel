package dev.invoiceintel.eval.metrics.pii;

import java.util.Objects;

/**
 * A typed character range {@code [start, end)} in one page's text (the Text stage's {@code
 * PageText}, architecture.md §6.3), holding no value: a detected {@code pii_span} row or a located
 * label occurrence.
 */
public record PageSpan(PiiSpanType type, int page, int start, int end) {

  public PageSpan {
    Objects.requireNonNull(type, "type");
    if (page < 1 || start < 0 || end <= start) {
      throw new IllegalArgumentException(
          "invalid span on page " + page + ": [" + start + ", " + end + ")");
    }
  }

  int length() {
    return end - start;
  }
}
