package dev.invoiceintel.eval.metrics.pii;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * PII span counts for one document or, summed with {@link #plus}, a whole run. {@code byType} holds
 * every {@link PiiSpanType}: expected-side counts under the label's type, detected-side counts
 * under the detected span's type.
 */
public record PiiScore(PiiCounts total, Map<PiiSpanType, PiiCounts> byType) {

  public static final PiiScore EMPTY = new PiiScore(PiiCounts.ZERO, Map.of());

  public PiiScore {
    Objects.requireNonNull(total, "total");
    EnumMap<PiiSpanType, PiiCounts> all = new EnumMap<>(PiiSpanType.class);
    for (PiiSpanType type : PiiSpanType.values()) {
      all.put(type, byType.getOrDefault(type, PiiCounts.ZERO));
    }
    byType = Collections.unmodifiableMap(all);
  }

  /** The counts for one type. */
  public PiiCounts of(PiiSpanType type) {
    return byType.get(type);
  }

  public PiiScore plus(PiiScore other) {
    EnumMap<PiiSpanType, PiiCounts> sum = new EnumMap<>(PiiSpanType.class);
    for (PiiSpanType type : PiiSpanType.values()) {
      sum.put(type, of(type).plus(other.of(type)));
    }
    return new PiiScore(total.plus(other.total), sum);
  }
}
