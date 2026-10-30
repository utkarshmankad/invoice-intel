package dev.invoiceintel.common;

import java.util.Objects;
import java.util.UUID;

/** ID of one stored extraction, the {@code extraction.id} column (architecture.md §7). */
public record ExtractionId(UUID value) {

  public ExtractionId {
    Objects.requireNonNull(value, "value");
  }

  public static ExtractionId random() {
    return new ExtractionId(UUID.randomUUID());
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
