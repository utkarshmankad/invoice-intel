package dev.invoiceintel.common.metrics;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class InvoiceMetricsTest {

  @Test
  void none_everyEvent_doesNothing() {
    var metrics = InvoiceMetrics.NONE;

    assertThatCode(
            () -> {
              metrics.stageEnded("TEXT", "SUCCEEDED", Duration.ofMillis(5));
              metrics.decided("NEEDS_REVIEW");
              metrics.llmCall("ollama", "local", 10, 5, BigDecimal.ZERO);
              metrics.piiSpan("PHONE", "regex:mobile");
              metrics.piiLeak();
            })
        .doesNotThrowAnyException();
  }
}
