package dev.invoiceintel.extract;

import dev.invoiceintel.pii.RedactedContent;

/**
 * The only path to a remote model (PII-1, architecture.md §6.4). It takes only {@link
 * RedactedContent}; there is no overload with a {@code String}, {@code byte[]}, text layer or blob
 * key, and ArchUnit keeps it that way.
 */
public interface LlmGateway {

  /**
   * Asks the request's model for structured output, with one repair retry on an invalid response
   * (EXT-1), and records every call (EXT-4).
   *
   * @throws IllegalArgumentException if the model is not in the registry or its provider has no
   *     client
   */
  <T> LlmResult<T> extract(RedactedContent content, ExtractionRequest<T> request);
}
