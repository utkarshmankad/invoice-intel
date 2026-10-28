package dev.invoiceintel.pii;

/**
 * Characters {@code [start, end)} of a page's {@link PageText}; positions only, never the text.
 *
 * @param pageNo 1-based page number
 */
public record TextRange(int pageNo, int start, int end) {

  public TextRange {
    if (pageNo < 1) {
      throw new IllegalArgumentException("pageNo must be >= 1");
    }
    if (start < 0 || end <= start) {
      throw new IllegalArgumentException("range must satisfy 0 <= start < end");
    }
  }

  /** Whether {@code span} lies entirely inside this range. */
  public boolean contains(PiiSpan span) {
    return span.pageNo() == pageNo && span.start() >= start && span.end() <= end;
  }
}
