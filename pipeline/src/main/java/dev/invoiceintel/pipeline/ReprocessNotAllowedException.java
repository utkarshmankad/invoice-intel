package dev.invoiceintel.pipeline;

import java.io.Serial;

/**
 * A reprocess request names a stage the document hasn't reached yet, or one that has no handler
 * yet. The message holds the stage and state names only (PII-8).
 */
public class ReprocessNotAllowedException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public ReprocessNotAllowedException(String message) {
    super(message);
  }
}
