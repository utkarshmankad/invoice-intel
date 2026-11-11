package dev.invoiceintel.eval.metrics.fields;

import dev.invoiceintel.common.extraction.Field;
import dev.invoiceintel.common.extraction.InvoiceExtraction;
import dev.invoiceintel.common.extraction.LineItem;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import tools.jackson.databind.JsonNode;

/**
 * Turns a label's {@code fields} (architecture.md §9.2) and an {@link InvoiceExtraction} (§6.5)
 * into {@link DocumentValues}, the plain form {@link FieldMetrics} compares. Values are rendered as
 * text the way {@link ValueComparison} parses them: dates ISO, amounts as plain decimals, enums and
 * booleans by name.
 *
 * <p>The results hold real values when built from a label; never log or print them (pii.md).
 */
public final class DocumentValuesMapper {

  private DocumentValuesMapper() {}

  /**
   * The values of a label checked by {@code LabelValidator}.
   *
   * @param label the whole label file
   * @param tokenizer turns a printed invoice or order number into the PII stage's token; applied to
   *     the {@link HeaderField.Kind#TOKEN} fields that aren't null
   */
  public static DocumentValues fromLabel(JsonNode label, UnaryOperator<String> tokenizer) {
    JsonNode fields = label.get("fields");
    Map<HeaderField, String> header = new EnumMap<>(HeaderField.class);
    for (HeaderField field : HeaderField.values()) {
      String value = text(fields.get(field.labelKey()));
      if (value != null && field.kind() == HeaderField.Kind.TOKEN) {
        value = tokenizer.apply(value);
      }
      header.put(field, value);
    }
    List<DocumentValues.LineItem> items = new ArrayList<>();
    JsonNode lineItems = fields.get("lineItems");
    if (lineItems != null && lineItems.isArray()) {
      for (JsonNode item : lineItems) {
        String total = text(item.get("lineTotal"));
        items.add(
            new DocumentValues.LineItem(
                text(item.get("description")), total == null ? null : new BigDecimal(total)));
      }
    }
    return new DocumentValues(header, items);
  }

  /** The values of an extraction; a missing field is {@code null}. */
  public static DocumentValues fromExtraction(InvoiceExtraction extraction) {
    Map<HeaderField, String> header = new EnumMap<>(HeaderField.class);
    for (HeaderField field : HeaderField.values()) {
      header.put(field, text(field(extraction, field)));
    }
    List<DocumentValues.LineItem> items = new ArrayList<>();
    if (extraction.lineItems() != null) {
      for (LineItem item : extraction.lineItems()) {
        items.add(
            new DocumentValues.LineItem(stringValue(item.description()), value(item.lineTotal())));
      }
    }
    return new DocumentValues(header, items);
  }

  private static Field<?> field(InvoiceExtraction e, HeaderField field) {
    return switch (field) {
      case DOCUMENT_CLASS -> e.documentClass();
      case INVOICE_NUMBER -> e.invoiceNumber();
      case INVOICE_DATE -> e.invoiceDate();
      case ORDER_NUMBER -> e.orderNumber();
      case ORDER_DATE -> e.orderDate();
      case SELLER_NAME -> e.sellerName();
      case SELLER_GSTIN -> e.sellerGstin();
      case SELLER_PAN -> e.sellerPan();
      case SELLER_ADDRESS -> e.sellerAddress();
      case PLACE_OF_SUPPLY -> e.placeOfSupply();
      case PLACE_OF_DELIVERY -> e.placeOfDelivery();
      case BUYER_STATE -> e.buyerState();
      case REVERSE_CHARGE -> e.reverseCharge();
      case CURRENCY -> e.currency();
      case SUBTOTAL -> e.subtotal();
      case TOTAL_TAX -> e.totalTax();
      case GRAND_TOTAL -> e.grandTotal();
      case AMOUNT_IN_WORDS -> e.amountInWords();
      case ORIGINAL_INVOICE_REF -> e.originalInvoiceRef();
    };
  }

  private static String text(Field<?> field) {
    if (field == null || field.value() == null) {
      return null;
    }
    Object value = field.value();
    if (value instanceof BigDecimal amount) {
      return amount.toPlainString();
    }
    if (value instanceof Enum<?> constant) {
      return constant.name();
    }
    return value.toString();
  }

  private static String stringValue(Field<String> field) {
    return field == null ? null : field.value();
  }

  private static BigDecimal value(Field<BigDecimal> field) {
    return field == null ? null : field.value();
  }

  private static String text(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    return node.isString() ? node.stringValue() : node.asString();
  }
}
