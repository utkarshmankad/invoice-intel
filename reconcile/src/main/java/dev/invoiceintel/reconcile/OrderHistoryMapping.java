package dev.invoiceintel.reconcile;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The column mapping of one order-history CSV layout (REC-1): every column name of the file's
 * header, and for the imported ones the {@link OrderHistoryField} they fill. A CSV uses this
 * mapping when its header holds exactly these column names, in any order.
 *
 * @param file the export file the mapping was written for, e.g. {@code order-history-1.csv}
 * @param kind the export's kind as written in the mapping file, e.g. {@code RETAIL}
 * @param columns every column name, mapped to its field or to {@code null} when not imported
 */
public record OrderHistoryMapping(
    String file, String kind, Map<String, OrderHistoryField> columns) {

  public OrderHistoryMapping {
    Objects.requireNonNull(file, "file");
    Objects.requireNonNull(kind, "kind");
    // LinkedHashMap keeps the order of the mapping file and allows null targets.
    columns = Collections.unmodifiableMap(new LinkedHashMap<>(columns));
    var seen = new EnumMap<OrderHistoryField, String>(OrderHistoryField.class);
    for (var entry : columns.entrySet()) {
      Objects.requireNonNull(entry.getKey(), "column name");
      var field = entry.getValue();
      if (field != null && seen.put(field, entry.getKey()) != null) {
        throw new IllegalArgumentException(
            "mapping " + file + " maps more than one column to " + field.target());
      }
    }
    if (!seen.containsKey(OrderHistoryField.ORDER_TOKEN)) {
      throw new IllegalArgumentException(
          "mapping " + file + " maps no column to " + OrderHistoryField.ORDER_TOKEN.target());
    }
  }

  /** True when {@code header} holds exactly this mapping's column names. */
  public boolean matches(List<String> header) {
    Set<String> names = new HashSet<>(header);
    return names.size() == header.size() && names.equals(columns.keySet());
  }

  /** The imported fields, keyed by field, with their column names. */
  public Map<OrderHistoryField, String> fields() {
    var fields = new EnumMap<OrderHistoryField, String>(OrderHistoryField.class);
    columns.forEach(
        (name, field) -> {
          if (field != null) {
            fields.put(field, name);
          }
        });
    return fields;
  }
}
