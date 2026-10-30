package dev.invoiceintel.pii;

import java.util.Objects;
import java.util.Set;

/**
 * Outcome of a {@link LeakChecker} run (PII-6). It names only the types of the values found, never
 * the values or where they were found (PII-8).
 *
 * @param leakedTypes types of the known real values found in the response; empty if none
 */
public record LeakCheckResult(Set<PiiType> leakedTypes) {

  private static final LeakCheckResult CLEAN = new LeakCheckResult(Set.of());

  public LeakCheckResult {
    Objects.requireNonNull(leakedTypes, "leakedTypes");
    leakedTypes = Set.copyOf(leakedTypes);
  }

  /** No known value was found. */
  public static LeakCheckResult clean() {
    return CLEAN;
  }

  /** At least one known real value is in the response: the document fails with {@code PII_LEAK}. */
  public boolean leaked() {
    return !leakedTypes.isEmpty();
  }
}
