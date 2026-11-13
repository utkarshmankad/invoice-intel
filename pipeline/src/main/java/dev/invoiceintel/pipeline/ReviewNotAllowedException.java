package dev.invoiceintel.pipeline;

import java.io.Serial;

/**
 * A correction or approval of a document that isn't waiting for review (REV-3). The message holds
 * state names only (PII-8).
 */
public class ReviewNotAllowedException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public ReviewNotAllowedException(String message) {
    super(message);
  }
}
