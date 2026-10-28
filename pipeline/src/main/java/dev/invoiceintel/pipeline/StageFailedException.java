package dev.invoiceintel.pipeline;

import java.io.Serial;
import java.util.Objects;

/**
 * A stage could not process a document. The code is stored as the document's {@code failure_code}
 * and is the whole message, so it must never contain document content (PII-8).
 */
public class StageFailedException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final String code;

  public StageFailedException(String code) {
    super(Objects.requireNonNull(code, "code"));
    this.code = code;
  }

  /** Machine-readable failure reason, e.g. {@code OCR_FAILED}. */
  public String code() {
    return code;
  }
}
