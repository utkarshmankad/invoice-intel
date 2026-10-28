package dev.invoiceintel.ingest.internal;

import java.util.Arrays;
import java.util.Optional;

/** The accepted upload types (ING-1) and the extension of their raw blob (architecture.md §9.1). */
public enum FileType {
  PDF("application/pdf", "pdf"),
  JPEG("image/jpeg", "jpg"),
  PNG("image/png", "png");

  private final String mimeType;
  private final String extension;

  FileType(String mimeType, String extension) {
    this.mimeType = mimeType;
    this.extension = extension;
  }

  public String mimeType() {
    return mimeType;
  }

  public String extension() {
    return extension;
  }

  /** The accepted type for {@code mimeType}, or empty if uploads of it are rejected. */
  public static Optional<FileType> ofMimeType(String mimeType) {
    return Arrays.stream(values()).filter(t -> t.mimeType.equals(mimeType)).findFirst();
  }
}
