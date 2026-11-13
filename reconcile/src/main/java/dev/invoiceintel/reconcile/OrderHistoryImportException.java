package dev.invoiceintel.reconcile;

import java.util.Objects;

/**
 * An order-history file that can't be imported at all. The message names the code only, never file
 * content (PII-8).
 */
public final class OrderHistoryImportException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** What is wrong with the file. */
  public enum Code {
    /** The file has no header line. */
    EMPTY_FILE,
    /** A quoted field never ends. */
    MALFORMED_CSV,
    /** No mapping in the mapping file has exactly the file's column names. */
    UNKNOWN_COLUMNS
  }

  private final Code code;

  public OrderHistoryImportException(Code code) {
    super("order-history import failed: " + Objects.requireNonNull(code, "code"));
    this.code = code;
  }

  public Code code() {
    return code;
  }
}
