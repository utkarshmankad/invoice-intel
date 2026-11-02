package dev.invoiceintel.extract;

import dev.invoiceintel.common.metrics.InvoiceMetrics;
import java.util.Objects;

/**
 * Where {@link LlmGateway} records each call (EXT-4). {@link JdbcLlmCallLog} writes {@code
 * llm_call} rows; tests collect them in memory.
 */
@FunctionalInterface
public interface LlmCallLog {

  void record(LlmCall call);

  /**
   * Records each call in {@code log}, then reports its tokens and cost to {@code metrics}
   * (architecture.md §12). A call is reported even if the stage later rolls back, since its tokens
   * were spent.
   */
  static LlmCallLog metered(LlmCallLog log, InvoiceMetrics metrics) {
    Objects.requireNonNull(log, "log");
    Objects.requireNonNull(metrics, "metrics");
    return call -> {
      log.record(call);
      metrics.llmCall(
          call.provider(), call.modelId(), call.inputTokens(), call.outputTokens(), call.costUsd());
    };
  }
}
