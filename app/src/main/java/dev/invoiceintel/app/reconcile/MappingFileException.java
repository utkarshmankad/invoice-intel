package dev.invoiceintel.app.reconcile;

/**
 * The order-history mapping file is missing, unreadable or invalid. The message names the problem,
 * never values (the file holds column names only, plan D2).
 */
public final class MappingFileException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public MappingFileException(String message, Throwable cause) {
    super(message, cause);
  }

  public MappingFileException(String message) {
    super(message);
  }
}
