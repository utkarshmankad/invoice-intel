package dev.invoiceintel.storage;

import java.util.Optional;

/**
 * Object storage for originals, page images and redacted output (NFR-11, architecture.md §9.1).
 * Implementations throw {@link BlobStoreException} when the store can't be reached.
 */
public interface BlobStore {

  /** Stores {@code content} under {@code key}, replacing any existing object. */
  void put(BlobKey key, byte[] content, String contentType);

  /** Content of the object, or empty if there is none under {@code key}. */
  Optional<byte[]> get(BlobKey key);

  /** Deletes the object; deleting a missing object is not an error. */
  void delete(BlobKey key);
}
