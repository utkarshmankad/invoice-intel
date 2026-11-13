package dev.invoiceintel.reconcile;

import java.util.Objects;
import java.util.UUID;

/** Identifies one order-history import; every row it stored carries it as {@code import_id}. */
public record ImportId(UUID value) {

  public ImportId {
    Objects.requireNonNull(value, "value");
  }

  public static ImportId random() {
    return new ImportId(UUID.randomUUID());
  }
}
