package dev.invoiceintel.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** The §12 metric names, types and tags (architecture.md §12, NFR-6). */
class MicrometerInvoiceMetricsTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final MicrometerInvoiceMetrics metrics = new MicrometerInvoiceMetrics(registry);

  @Test
  void constructor_registersLeakCounterAtZero() {
    assertThat(registry.get("invoice.pii.leak").counter().count()).isZero();
  }

  @Test
  void piiLeak_incrementsUntaggedLeakCounter() {
    metrics.piiLeak();

    var counter = registry.get("invoice.pii.leak").counter();
    assertThat(counter.count()).isEqualTo(1.0);
    assertThat(counter.getId().getTags()).isEmpty();
  }

  @Test
  void stageEnded_recordsTimerByStageAndOutcome() {
    metrics.stageEnded("PII", "SUCCEEDED", Duration.ofMillis(40));
    metrics.stageEnded("PII", "SUCCEEDED", Duration.ofMillis(60));
    metrics.stageEnded("PII", "FAILED", Duration.ofMillis(5));

    var succeeded =
        registry.get("invoice.stage.duration").tag("stage", "PII").tag("outcome", "SUCCEEDED");
    assertThat(succeeded.timer().count()).isEqualTo(2);
    assertThat(succeeded.timer().totalTime(TimeUnit.MILLISECONDS)).isEqualTo(100.0);
    assertThat(registry.get("invoice.stage.duration").tag("outcome", "FAILED").timer().count())
        .isEqualTo(1);
  }

  @Test
  void decided_countsByOutcome() {
    metrics.decided("AUTO_APPROVED");
    metrics.decided("NEEDS_REVIEW");
    metrics.decided("NEEDS_REVIEW");

    assertThat(registry.get("invoice.decision").tag("outcome", "NEEDS_REVIEW").counter().count())
        .isEqualTo(2.0);
    assertThat(registry.get("invoice.decision").tag("outcome", "AUTO_APPROVED").counter().count())
        .isEqualTo(1.0);
  }

  @Test
  void llmCall_countsTokensByDirectionAndCostByModel() {
    metrics.llmCall("anthropic", "claude-test", 1200, 300, new BigDecimal("0.0081"));
    metrics.llmCall("anthropic", "claude-test", 800, 200, new BigDecimal("0.0019"));

    var tokens = registry.get("invoice.llm.tokens").tag("provider", "anthropic");
    assertThat(tokens.tag("direction", "input").counter().count()).isEqualTo(2000.0);
    assertThat(
            registry
                .get("invoice.llm.tokens")
                .tag("model", "claude-test")
                .tag("direction", "output")
                .counter()
                .count())
        .isEqualTo(500.0);
    var cost = registry.get("invoice.llm.cost").tag("model", "claude-test").counter();
    assertThat(cost.count()).isCloseTo(0.01, offset(1e-9));
    assertThat(cost.getId().getBaseUnit()).isEqualTo("usd");
  }

  @Test
  void piiSpan_countsByTypeAndDetector() {
    metrics.piiSpan("PHONE", "regex:mobile");
    metrics.piiSpan("PHONE", "regex:mobile");
    metrics.piiSpan("PERSON", "address-block:name+ner:distilbert-conll03");

    assertThat(
            registry
                .get("invoice.pii.spans")
                .tag("type", "PHONE")
                .tag("detector", "regex:mobile")
                .counter()
                .count())
        .isEqualTo(2.0);
    assertThat(registry.get("invoice.pii.spans").tag("type", "PERSON").counter().count())
        .isEqualTo(1.0);
  }
}
