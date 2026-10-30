package dev.invoiceintel.extract;

import java.util.List;
import java.util.Objects;

/** Result of {@link LlmGateway#extract}: the validated value, or why there is none. */
public sealed interface LlmResult<T> {

  /** Every call made for this result, in order (one, or two with the repair retry). */
  List<LlmCall> calls();

  /**
   * The raw text of every response, in call order, failed attempts included, for the leak check
   * (PII-6, architecture.md §6.3). It may hold whatever the model wrote, so it is never logged,
   * stored or printed (PII-8).
   */
  List<String> responses();

  record Succeeded<T>(T value, List<LlmCall> calls, List<String> responses)
      implements LlmResult<T> {

    public Succeeded {
      Objects.requireNonNull(value, "value");
      calls = List.copyOf(calls);
      responses = List.copyOf(responses);
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
  record Failed<T>(String code, List<LlmCall> calls, List<String> responses)
      implements LlmResult<T> {

    public static final String INVALID_OUTPUT = "LLM_INVALID_OUTPUT";
    public static final String ERROR = "LLM_ERROR";

    public Failed {
      Objects.requireNonNull(code, "code");
      calls = List.copyOf(calls);
      responses = List.copyOf(responses);
    }

    /** Never prints the responses (PII-8). */
    @Override
    public String toString() {
      return "Failed[code=" + code + ", calls=" + calls.size() + "]";
    }
  }
}
