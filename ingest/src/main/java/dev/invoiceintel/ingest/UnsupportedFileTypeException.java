package dev.invoiceintel.ingest;

import java.io.Serial;

/**
 * An uploaded file's content is not one of the accepted types (ING-1, ING-3). The message holds the
 * file's position in the request and the detected MIME type only, never content (PII-8).
 */
public class UnsupportedFileTypeException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final int fileIndex;
  private final String detectedType;

  public UnsupportedFileTypeException(int fileIndex, String detectedType) {
    super("file " + fileIndex + " has unsupported type " + detectedType);
    this.fileIndex = fileIndex;
    this.detectedType = detectedType;
  }

  /** Zero-based position of the rejected file in the upload. */
  public int fileIndex() {
    return fileIndex;
  }

  /** MIME type detected from the content. */
  public String detectedType() {
    return detectedType;
  }
}
