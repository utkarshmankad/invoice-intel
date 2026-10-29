package dev.invoiceintel.extract;

import java.util.List;
import java.util.Objects;

/** Result of {@link LlmGateway#extract}: the validated value, or why there is none. */
public sealed interface LlmResult<T> {

  /** Every call made for this result, in order (one, or two with the repair retry). */
  List<LlmCall> calls();

  record Succeeded<T>(T value, List<LlmCall> calls) implements LlmResult<T> {

    public Succeeded {
      Objects.requireNonNull(value, "value");
      calls = List.copyOf(calls);
    }

    /** The value may hold pseudonymized fields; it is never printed (PII-8). */
    @Override
    public String toString() {
      return "Succeeded[calls=" + calls.size() + "]";
    }
  }

  /**
   * @param code {@code LLM_INVALID_OUTPUT} after the repair retry also failed, {@code LLM_ERROR} if
   *     the provider call failed
   */
  record Failed<T>(String code, List<LlmCall> calls) implements LlmResult<T> {

    public static final String INVALID_OUTPUT = "LLM_INVALID_OUTPUT";
    public static final String ERROR = "LLM_ERROR";

    public Failed {
      Objects.requireNonNull(code, "code");
      calls = List.copyOf(calls);
    }
  }
}
