package dev.invoiceintel.storage;

import java.io.Serial;

/**
 * A storage operation failed. The message names the operation and bucket only, never object content
 * (PII-8).
 */
public class BlobStoreException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public BlobStoreException(String message, Throwable cause) {
    super(message, cause);
  }
}
