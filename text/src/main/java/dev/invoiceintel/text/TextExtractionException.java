package dev.invoiceintel.text;

import java.io.Serial;

/** A file could not be read as a PDF. The message never quotes document content (PII-8). */
public class TextExtractionException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public TextExtractionException(String message, Throwable cause) {
    super(message, cause);
  }
}
