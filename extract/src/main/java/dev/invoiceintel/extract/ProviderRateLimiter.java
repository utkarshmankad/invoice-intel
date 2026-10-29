package dev.invoiceintel.extract;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * Caps concurrent calls per provider (architecture.md §5.2): LLM jobs run on virtual threads, so
 * without a cap a burst of documents would hit the provider's rate limit.
 */
public final class ProviderRateLimiter {

  private final Map<String, Integer> limits;
  private final int defaultLimit;
  private final Map<String, Semaphore> semaphores = new ConcurrentHashMap<>();

  /**
   * @param limits max concurrent calls by provider name
   * @param defaultLimit limit of a provider not in {@code limits}
   */
  public ProviderRateLimiter(Map<String, Integer> limits, int defaultLimit) {
    this.limits = Map.copyOf(Objects.requireNonNull(limits, "limits"));
    if (defaultLimit < 1 || this.limits.values().stream().anyMatch(limit -> limit < 1)) {
      throw new IllegalArgumentException("limits must be >= 1");
    }
    this.defaultLimit = defaultLimit;
  }

  /** Runs {@code call} once a slot of {@code provider} is free. */
  public <T> T call(String provider, Supplier<T> call) {
    Semaphore semaphore =
        semaphores.computeIfAbsent(
            provider, p -> new Semaphore(limits.getOrDefault(p, defaultLimit), true));
    try {
      semaphore.acquire();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted waiting for provider " + provider, e);
    }
    try {
      return call.get();
    } finally {
      semaphore.release();
    }
  }

  /** Free slots of {@code provider} now (for tests and metrics). */
  int available(String provider) {
    Semaphore semaphore = semaphores.get(provider);
    return semaphore == null
        ? limits.getOrDefault(provider, defaultLimit)
        : semaphore.availablePermits();
  }
}
