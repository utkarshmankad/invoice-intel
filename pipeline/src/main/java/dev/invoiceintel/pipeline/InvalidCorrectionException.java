package dev.invoiceintel.pipeline;

import java.io.Serial;

/**
 * A correction names no correctable field, or has a value the field can't hold (REV-3, ADR-30). The
 * message names the field path only, never the value (PII-8).
 */
public class InvalidCorrectionException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  InvalidCorrectionException(String message, Throwable cause) {
    super(message, cause);
  }
}
