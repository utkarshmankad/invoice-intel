package dev.invoiceintel.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ProviderRateLimiterTest {

  private final ProviderRateLimiter limiter = new ProviderRateLimiter(Map.of("openai", 1), 3);

  @Test
  void call_holdsASlotWhileRunning() {
    int during = limiter.call("openai", () -> limiter.available("openai"));

    assertThat(during).isZero();
    assertThat(limiter.available("openai")).isEqualTo(1);
  }

  @Test
  void call_providerWithoutLimit_usesDefault() {
    int during = limiter.call("ollama", () -> limiter.available("ollama"));

    assertThat(during).isEqualTo(2);
    assertThat(limiter.available("ollama")).isEqualTo(3);
  }

  @Test
  void call_throwing_releasesSlot() {
    assertThatThrownBy(
            () ->
                limiter.call(
                    "openai",
                    () -> {
                      throw new IllegalStateException("boom");
                    }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(limiter.available("openai")).isEqualTo(1);
  }

  @Test
  void call_interrupted_throwsAndKeepsInterruptFlag() {
    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(() -> limiter.call("openai", () -> 1))
          .isInstanceOf(IllegalStateException.class);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void new_limitBelowOne_isRejected() {
    assertThatThrownBy(() -> new ProviderRateLimiter(Map.of("openai", 0), 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ProviderRateLimiter(Map.of(), 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
