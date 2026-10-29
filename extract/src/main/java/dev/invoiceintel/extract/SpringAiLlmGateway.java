package dev.invoiceintel.extract;

import dev.invoiceintel.pii.RedactedContent;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;

/**
 * {@link LlmGateway} on Spring AI chat models (architecture.md §6.5). The output schema comes from
 * the request's record type; a response that doesn't parse or fails Bean Validation gets one repair
 * retry with the error, then the result fails (EXT-1). Every call is recorded with tokens, cost and
 * latency (EXT-4) and runs under the provider's concurrency cap (§5.2).
 */
public final class SpringAiLlmGateway implements LlmGateway {

  static final String STAGE = "EXTRACT";
  private static final int MAX_ATTEMPTS = 2;
  private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
  // Per-call costs are fractions of a cent; 6 places keep their sum per run exact enough (NFR-4).
  private static final int COST_SCALE = 6;
  private static final Logger log = LoggerFactory.getLogger(SpringAiLlmGateway.class);

  private final ModelRegistry registry;
  private final Map<String, ChatModel> clients;
  private final ProviderRateLimiter rateLimiter;
  private final Validator validator;
  private final LlmCallLog callLog;

  /**
   * @param clients chat client by provider name ({@link ModelSpec#provider()})
   */
  public SpringAiLlmGateway(
      ModelRegistry registry,
      Map<String, ChatModel> clients,
      ProviderRateLimiter rateLimiter,
      Validator validator,
      LlmCallLog callLog) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.clients = Map.copyOf(Objects.requireNonNull(clients, "clients"));
    this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
    this.validator = Objects.requireNonNull(validator, "validator");
    this.callLog = Objects.requireNonNull(callLog, "callLog");
  }

  @Override
  public <T> LlmResult<T> extract(RedactedContent content, ExtractionRequest<T> request) {
    Objects.requireNonNull(content, "content");
    Objects.requireNonNull(request, "request");
    ModelSpec model =
        registry
            .find(request.model())
            .orElseThrow(() -> new IllegalArgumentException("unknown model: " + request.model()));
    ChatModel client = clients.get(model.provider());
    if (client == null) {
      throw new IllegalArgumentException("no client for provider: " + model.provider());
    }

    var converter = new BeanOutputConverter<>(request.outputType());
    var messages = new ArrayList<Message>();
    messages.add(new SystemMessage(request.instructions() + "\n\n" + converter.getFormat()));
    // TEXT strategy (EXT-2): redacted lines with the markers the model cites as evidence.
    messages.add(new UserMessage(TextLines.markedText(content)));
    // Spring AI 2.0 providers accept only their own options type, so the model ID goes onto
    // the client's defaults.
    ChatOptions options = client.getOptions().mutate().model(model.modelId()).build();

    var calls = new ArrayList<LlmCall>();
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      long started = System.nanoTime();
      ChatResponse response;
      try {
        Prompt prompt = new Prompt(List.copyOf(messages), options);
        response = rateLimiter.call(model.provider(), () -> client.call(prompt));
      } catch (RuntimeException e) {
        // The exception may quote the request, so it isn't logged (PII-8); the pipeline's MDC
        // carries the document ID and stage, and the llm_call row the details.
        calls.add(call(content, request, model, attempt, null, started, LlmOutcome.ERROR));
        callLog.record(calls.getLast());
        log.warn("LLM call failed");
        return new LlmResult.Failed<>(LlmResult.Failed.ERROR, calls);
      }
      String raw = text(response);
      Parsed<T> parsed = parse(converter, raw);
      LlmOutcome outcome =
          parsed.value() != null ? LlmOutcome.SUCCEEDED : LlmOutcome.INVALID_OUTPUT;
      calls.add(call(content, request, model, attempt, response, started, outcome));
      callLog.record(calls.getLast());
      if (parsed.value() != null) {
        return new LlmResult.Succeeded<>(parsed.value(), calls);
      }
      log.info("LLM output invalid");
      String error = parsed.error();
      // The model only saw redacted content, so echoing its answer and the error back is safe.
      messages.add(new AssistantMessage(raw));
      messages.add(
          new UserMessage(
              "Your previous response was invalid: "
                  + error
                  + "\nReply again with only the JSON object, following the schema."));
    }
    return new LlmResult.Failed<>(LlmResult.Failed.INVALID_OUTPUT, calls);
  }

  private static String text(ChatResponse response) {
    Generation generation = response == null ? null : response.getResult();
    if (generation == null || generation.getOutput() == null) {
      return "";
    }
    String text = generation.getOutput().getText();
    return text == null ? "" : text;
  }

  /** A valid value, or the error to send back for the repair retry. */
  private record Parsed<T>(T value, String error) {}

  private <T> Parsed<T> parse(BeanOutputConverter<T> converter, String raw) {
    T value;
    try {
      value = converter.convert(raw);
    } catch (RuntimeException e) {
      return new Parsed<>(null, "not valid JSON for the schema: " + e.getMessage());
    }
    if (value == null) {
      return new Parsed<>(null, "empty response");
    }
    var violations = validator.validate(value);
    if (violations.isEmpty()) {
      return new Parsed<>(value, null);
    }
    return new Parsed<>(
        null,
        violations.stream()
            .map(v -> v.getPropertyPath() + " " + v.getMessage())
            .sorted(Comparator.naturalOrder())
            .collect(Collectors.joining("; ")));
  }

  private static LlmCall call(
      RedactedContent content,
      ExtractionRequest<?> request,
      ModelSpec model,
      int attempt,
      ChatResponse response,
      long startedNanos,
      LlmOutcome outcome) {
    long latencyMs = (System.nanoTime() - startedNanos) / 1_000_000;
    Usage usage =
        response == null || response.getMetadata() == null
            ? null
            : response.getMetadata().getUsage();
    int input = usage == null ? 0 : orZero(usage.getPromptTokens());
    int output = usage == null ? 0 : orZero(usage.getCompletionTokens());
    return new LlmCall(
        content.documentId(),
        STAGE,
        model.provider(),
        model.modelId(),
        request.prompt().id(),
        attempt,
        input,
        output,
        cost(model, input, output),
        latencyMs,
        outcome);
  }

  /** Tokens × configured price per million (EXT-4). */
  static BigDecimal cost(ModelSpec model, int inputTokens, int outputTokens) {
    return model
        .inputPricePerMillion()
        .multiply(BigDecimal.valueOf(inputTokens))
        .add(model.outputPricePerMillion().multiply(BigDecimal.valueOf(outputTokens)))
        .divide(MILLION, COST_SCALE, RoundingMode.HALF_UP);
  }

  private static int orZero(Integer value) {
    return value == null ? 0 : value;
  }
}
