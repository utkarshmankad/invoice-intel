package dev.invoiceintel.eval.metrics.pii;

import java.util.Objects;

/**
 * A label file's {@code piiSpans} entry (architecture.md §9.2): type, 1-based page and the text as
 * printed. {@link #toString()} leaves the text out (PII-8).
 */
public record LabelSpan(PiiSpanType type, int page, String text) {

  public LabelSpan {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(text, "text");
    if (page < 1) {
      throw new IllegalArgumentException("page must be >= 1: " + page);
    }
  }

  @Override
  public String toString() {
    return "LabelSpan[type=" + type + ", page=" + page + ", length=" + text.length() + "]";
  }
}
