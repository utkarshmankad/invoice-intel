package dev.invoiceintel.common.metrics;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Records the metrics of architecture.md §12 (NFR-6). Modules report events through this port so
 * only {@code app} depends on Micrometer. Every argument becomes a metric tag or value, so callers
 * pass enum names, status codes, configured provider/model IDs and detector names only: never field
 * values, document text or document IDs (PII-8; IDs would also make tags unbounded).
 *
 * <p>Every method does nothing by default; {@link #NONE} is the instance for code built without
 * metrics (unit tests, tools).
 */
public interface InvoiceMetrics {

  /** Records nothing. */
  InvoiceMetrics NONE = new InvoiceMetrics() {};

  /**
   * One stage run ended ({@code invoice.stage.duration}).
   *
   * @param stage the stage name, e.g. {@code PII}
   * @param outcome the run's end status: {@code SUCCEEDED}, {@code FAILED} or {@code SUPERSEDED}
   */
  default void stageEnded(String stage, String outcome, Duration duration) {}

  /**
   * A document got its VAL-8 decision ({@code invoice.decision}).
   *
   * @param outcome {@code AUTO_APPROVED}, {@code NEEDS_REVIEW} or {@code FAILED}
   */
  default void decided(String outcome) {}

  /**
   * One model call ({@code invoice.llm.tokens}, {@code invoice.llm.cost}).
   *
   * @param provider the registry's provider name
   * @param model the registry's model ID
   * @param costUsd the call's cost in USD (EXT-4)
   */
  default void llmCall(
      String provider, String model, long inputTokens, long outputTokens, BigDecimal costUsd) {}

  /**
   * One PII span was stored ({@code invoice.pii.spans}).
   *
   * @param type the {@code PiiType} name
   * @param detector the span's detector name as stored in {@code pii_span}, e.g. {@code
   *     regex:mobile}
   */
  default void piiSpan(String type, String detector) {}

  /** The leak check found known PII in a model response ({@code invoice.pii.leak}, PII-6). */
  default void piiLeak() {}
}
