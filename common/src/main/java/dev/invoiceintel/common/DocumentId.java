package dev.invoiceintel.common;

import java.util.Objects;
import java.util.UUID;

/** ID of one uploaded file, the {@code document.id} column (architecture.md §7). */
public record DocumentId(UUID value) {

  public DocumentId {
    Objects.requireNonNull(value, "value");
  }

  public static DocumentId random() {
    return new DocumentId(UUID.randomUUID());
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
