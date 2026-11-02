package dev.invoiceintel.eval.metrics.fields;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One document's header values and line items as plain values, from either a label (§9.2) or an
 * extraction (§6.5). A missing or {@code null} header value means the document doesn't show it.
 *
 * <p>Holds real values when built from a label; never log or print it (pii.md).
 */
public record DocumentValues(Map<HeaderField, String> header, List<LineItem> lineItems) {

  /** A line item as the line-item F1 sees it: description and line total. */
  public record LineItem(String description, BigDecimal lineTotal) {}

  public DocumentValues {
    EnumMap<HeaderField, String> copy = new EnumMap<>(HeaderField.class);
    if (header != null) {
      header.forEach((field, value) -> copy.put(Objects.requireNonNull(field), value));
    }
    // EnumMap allows null values, which Map.copyOf doesn't; wrap instead of copying.
    header = Collections.unmodifiableMap(copy);
    lineItems = lineItems == null ? List.of() : List.copyOf(lineItems);
  }

  /** The value of {@code field}, or {@code null} if the document doesn't show it. */
  public String value(HeaderField field) {
    return header.get(field);
  }

  @Override
  public String toString() {
    // Values may be real personal data (label files, pii.md): never print them.
    return "DocumentValues[" + header.size() + " header values, " + lineItems.size() + " lines]";
  }
}
