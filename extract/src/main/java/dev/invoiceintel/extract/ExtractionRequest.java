package dev.invoiceintel.extract;

import java.util.Objects;

/**
 * What {@link LlmGateway} asks the model for. The document itself comes only from the {@code
 * RedactedContent} argument (PII-1, architecture.md §6.4); {@code instructions} is the rendered
 * prompt and holds no document text.
 *
 * @param model registry name of the model ({@link ModelRegistry#find})
 * @param prompt the prompt the instructions were rendered from, recorded per call (EXT-4)
 * @param instructions system instructions; the gateway appends the output schema (EXT-1)
 * @param outputType record the response is parsed into and validated against
 */
public record ExtractionRequest<T>(
    String model, PromptRef prompt, String instructions, Class<T> outputType) {

  public ExtractionRequest {
    Objects.requireNonNull(model, "model");
    Objects.requireNonNull(prompt, "prompt");
    Objects.requireNonNull(instructions, "instructions");
    Objects.requireNonNull(outputType, "outputType");
  }

  /** Never prints the instructions. */
  @Override
  public String toString() {
    return "ExtractionRequest[model="
        + model
        + ", prompt="
        + prompt.id()
        + ", outputType="
        + outputType.getSimpleName()
        + "]";
  }
}
