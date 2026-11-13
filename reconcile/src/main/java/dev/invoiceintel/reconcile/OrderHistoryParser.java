package dev.invoiceintel.reconcile;

import dev.invoiceintel.reconcile.OrderHistoryImportException.Code;
import dev.invoiceintel.reconcile.internal.CsvReader;
import dev.invoiceintel.reconcile.internal.Values;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Reads an order-history CSV with the mapping its header matches (REC-1, architecture.md §6.7).
 * Order numbers are tokenized as each row is read, so real order numbers never leave this class.
 * Rows that can't be read are skipped and counted by {@link SkipReason}.
 */
public final class OrderHistoryParser {

  private final OrderTokenizer tokenizer;

  public OrderHistoryParser(OrderTokenizer tokenizer) {
    this.tokenizer = Objects.requireNonNull(tokenizer, "tokenizer");
  }

  /**
   * @throws OrderHistoryImportException if the file has no header, is malformed, or no mapping has
   *     exactly its column names
   */
  public Parsed parse(String csv, OrderHistoryMappings mappings) {
    var records =
        CsvReader.read(Objects.requireNonNull(csv, "csv"))
            .orElseThrow(() -> new OrderHistoryImportException(Code.MALFORMED_CSV));
    if (records.isEmpty()) {
      throw new OrderHistoryImportException(Code.EMPTY_FILE);
    }
    var header = records.getFirst().stream().map(String::strip).toList();
    var mapping =
        mappings
            .forHeader(header)
            .orElseThrow(() -> new OrderHistoryImportException(Code.UNKNOWN_COLUMNS));
    var index = new EnumMap<OrderHistoryField, Integer>(OrderHistoryField.class);
    mapping.fields().forEach((field, column) -> index.put(field, header.indexOf(column)));

    var items = new ArrayList<OrderHistoryItem>();
    var skipped = new EnumMap<SkipReason, Integer>(SkipReason.class);
    for (var record : records.subList(1, records.size())) {
      if (record.size() != header.size()) {
        skipped.merge(SkipReason.WRONG_FIELD_COUNT, 1, Integer::sum);
        continue;
      }
      Function<OrderHistoryField, String> cell =
          f -> index.containsKey(f) ? record.get(index.get(f)) : null;
      try {
        items.add(item(cell));
      } catch (Skip skip) {
        skipped.merge(skip.reason, 1, Integer::sum);
      }
    }
    return new Parsed(mapping, items, skipped);
  }

  private OrderHistoryItem item(Function<OrderHistoryField, String> cell) throws Skip {
    var orderNumber =
        Values.text(cell.apply(OrderHistoryField.ORDER_TOKEN))
            .orElseThrow(() -> new Skip(SkipReason.MISSING_ORDER_ID));
    LocalDate date =
        read(() -> Values.date(cell.apply(OrderHistoryField.ORDER_DATE)), SkipReason.BAD_DATE);
    Integer quantity =
        read(
            () -> Values.quantity(cell.apply(OrderHistoryField.QUANTITY)), SkipReason.BAD_QUANTITY);
    BigDecimal unitPrice =
        read(() -> Values.amount(cell.apply(OrderHistoryField.UNIT_PRICE)), SkipReason.BAD_AMOUNT);
    BigDecimal itemTotal =
        read(() -> Values.amount(cell.apply(OrderHistoryField.ITEM_TOTAL)), SkipReason.BAD_AMOUNT);
    return new OrderHistoryItem(
        tokenizer.token(orderNumber),
        date,
        Values.text(cell.apply(OrderHistoryField.PRODUCT_NAME)).orElse(null),
        Values.text(cell.apply(OrderHistoryField.ASIN)).orElse(null),
        quantity,
        unitPrice,
        itemTotal);
  }

  /** The parsed value or null when absent; a value that can't be read skips the row. */
  private static <T> T read(java.util.function.Supplier<Optional<T>> parse, SkipReason reason)
      throws Skip {
    try {
      return parse.get().orElse(null);
    } catch (IllegalArgumentException e) {
      throw new Skip(reason);
    }
  }

  /** Control flow for a skipped row; carries no row content. */
  private static final class Skip extends Exception {
    private static final long serialVersionUID = 1L;
    private final SkipReason reason;

    Skip(SkipReason reason) {
      super(reason.name(), null, false, false);
      this.reason = reason;
    }
  }

  /**
   * A parsed file: the mapping used, the readable rows, and how many rows were skipped per reason.
   */
  public record Parsed(
      OrderHistoryMapping mapping, List<OrderHistoryItem> items, Map<SkipReason, Integer> skipped) {

    public Parsed {
      Objects.requireNonNull(mapping, "mapping");
      items = List.copyOf(items);
      var copy = new EnumMap<SkipReason, Integer>(SkipReason.class);
      copy.putAll(skipped);
      skipped = Collections.unmodifiableMap(copy);
    }

    /** Rows skipped in all. */
    public int skippedCount() {
      return skipped.values().stream().mapToInt(Integer::intValue).sum();
    }
  }
}
