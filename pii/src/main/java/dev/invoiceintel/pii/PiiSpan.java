package dev.invoiceintel.pii;

import java.util.Objects;

/**
 * A detected piece of PII: characters {@code [start, end)} of a page's {@link PageText}. It holds
 * positions only, never the value, so it is safe to store and log (PII-8; {@code pii_span},
 * architecture.md §7).
 *
 * @param pageNo 1-based page number
 * @param detector name of the detector rule that found it, e.g. {@code regex:phone}
 */
public record PiiSpan(int pageNo, int start, int end, PiiType type, String detector) {

  public PiiSpan {
    if (pageNo < 1) {
      throw new IllegalArgumentException("pageNo must be >= 1");
    }
    if (start < 0 || end <= start) {
      throw new IllegalArgumentException("span must satisfy 0 <= start < end");
    }
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(detector, "detector");
  }
}
