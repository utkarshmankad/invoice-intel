package dev.invoiceintel.extract;

/**
 * Where {@link LlmGateway} records each call (EXT-4). {@link JdbcLlmCallLog} writes {@code
 * llm_call} rows; tests collect them in memory.
 */
@FunctionalInterface
public interface LlmCallLog {

  void record(LlmCall call);
}
