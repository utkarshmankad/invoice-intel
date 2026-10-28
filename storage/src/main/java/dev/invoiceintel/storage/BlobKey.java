package dev.invoiceintel.storage;

import java.util.Objects;

/**
 * Location of one object: bucket plus key, as stored in {@code blob_ref} (architecture.md §7,
 * §9.1). Keys are relative paths such as {@code raw/{documentId}/original.pdf}.
 */
public record BlobKey(String bucket, String key) {

  public BlobKey {
    Objects.requireNonNull(bucket, "bucket");
    Objects.requireNonNull(key, "key");
    if (!bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")) {
      throw new IllegalArgumentException("invalid bucket name");
    }
    if (key.isBlank() || key.startsWith("/") || key.contains("//") || hasDotSegment(key)) {
      throw new IllegalArgumentException("invalid object key");
    }
  }

  private static boolean hasDotSegment(String key) {
    for (String segment : key.split("/", -1)) {
      if (segment.equals(".") || segment.equals("..")) {
        return true;
      }
    }
    return false;
  }
}
