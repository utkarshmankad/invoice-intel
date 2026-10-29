package dev.invoiceintel.extract;

import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Writes one {@code llm_call} row per call (EXT-4, architecture.md §7, V6). */
public final class JdbcLlmCallLog implements LlmCallLog {

  private final JdbcClient jdbc;

  public JdbcLlmCallLog(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public void record(LlmCall call) {
    jdbc.sql(
            """
            INSERT INTO llm_call (id, document_id, stage, provider, model_id, prompt_version,
                attempt, input_tokens, output_tokens, cost_usd, latency_ms, outcome)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)
        .params(
            UUID.randomUUID(),
            call.documentId().value(),
            call.stage(),
            call.provider(),
            call.modelId(),
            call.promptVersion(),
            call.attempt(),
            call.inputTokens(),
            call.outputTokens(),
            call.costUsd(),
            call.latencyMs(),
            call.outcome().name())
        .update();
  }
}
