package dev.invoiceintel.reconcile;

import java.util.List;
import java.util.Optional;

/** Every order-history CSV layout the import knows ({@code config/order-history-mapping.yml}). */
public record OrderHistoryMappings(List<OrderHistoryMapping> files) {

  public OrderHistoryMappings {
    files = List.copyOf(files);
  }

  /** The mapping whose column names are exactly {@code header}'s, if any. */
  public Optional<OrderHistoryMapping> forHeader(List<String> header) {
    return files.stream().filter(m -> m.matches(header)).findFirst();
  }
}
