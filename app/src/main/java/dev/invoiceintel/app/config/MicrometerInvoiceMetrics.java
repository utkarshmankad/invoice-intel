package dev.invoiceintel.app.config;

import dev.invoiceintel.common.metrics.InvoiceMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;

/**
 * The metrics of architecture.md §12 (NFR-6) on Micrometer; served at {@code /actuator/metrics}.
 * Tags carry stage names, status codes, provider/model IDs, PII types and detector names only,
 * never values, text or document IDs (PII-8).
 */
public final class MicrometerInvoiceMetrics implements InvoiceMetrics {

  static final String STAGE_DURATION = "invoice.stage.duration";
  static final String DECISION = "invoice.decision";
  static final String LLM_TOKENS = "invoice.llm.tokens";
  static final String LLM_COST = "invoice.llm.cost";
  static final String PII_SPANS = "invoice.pii.spans";
  static final String PII_LEAK = "invoice.pii.leak";

  private final MeterRegistry registry;
  private final Counter leaks;

  public MicrometerInvoiceMetrics(MeterRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "registry");
    // Registered up front so the alert metric reads 0, not "missing", until a leak (§12).
    this.leaks =
        Counter.builder(PII_LEAK)
            .description("Known PII values found in LLM responses (PII-6); must stay 0")
            .register(registry);
  }

  @Override
  public void stageEnded(String stage, String outcome, Duration duration) {
    Timer.builder(STAGE_DURATION)
        .description("Stage run time from job start to the run's end status")
        .tag("stage", stage)
        .tag("outcome", outcome)
        .register(registry)
        .record(duration);
  }

  @Override
  public void decided(String outcome) {
    Counter.builder(DECISION)
        .description("Documents by VAL-8 decision")
        .tag("outcome", outcome)
        .register(registry)
        .increment();
  }

  @Override
  public void llmCall(
      String provider, String model, long inputTokens, long outputTokens, BigDecimal costUsd) {
    tokens(provider, model, "input").increment(inputTokens);
    tokens(provider, model, "output").increment(outputTokens);
    // Micrometer counters are doubles; the exact cost stays in llm_call.cost_usd (EXT-4).
    Counter.builder(LLM_COST)
        .description("Model call cost in USD (EXT-4)")
        .baseUnit("usd")
        .tag("provider", provider)
        .tag("model", model)
        .register(registry)
        .increment(costUsd.doubleValue());
  }

  private Counter tokens(String provider, String model, String direction) {
    return Counter.builder(LLM_TOKENS)
        .description("Model call tokens")
        .baseUnit("tokens")
        .tag("provider", provider)
        .tag("model", model)
        .tag("direction", direction)
        .register(registry);
  }

  @Override
  public void piiSpan(String type, String detector) {
    Counter.builder(PII_SPANS)
        .description("Stored PII spans")
        .tag("type", type)
        .tag("detector", detector)
        .register(registry)
        .increment();
  }

  @Override
  public void piiLeak() {
    leaks.increment();
  }
}
