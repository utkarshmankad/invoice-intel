package dev.invoiceintel.app.api;

import dev.invoiceintel.common.extraction.Field;
import dev.invoiceintel.common.extraction.InvoiceExtraction;
import dev.invoiceintel.common.extraction.LineItem;
import dev.invoiceintel.common.extraction.TaxLine;
import dev.invoiceintel.pipeline.ReviewedExtraction;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes a reviewed document's extraction as an eval label (REV-4, architecture.md §9.2, ADR-31):
 * format version 1 with {@code "pseudonymized": true}, the token fields as the PII stage's tokens
 * and no PII spans. Values are written the way labels print them: amounts with exactly two
 * decimals, quantities and rates as plain decimals, dates ISO, enums by name.
 *
 * <p>An extraction a valid label can't hold (a blank description, an amount with more than two
 * significant decimals, a repeated or non-positive line number) is {@link NotExportable}: the
 * export never rounds or drops a reviewed value. Errors name the JSON path only (PII-8).
 */
final class ExportLabel {

  static final JsonMapper JSON = JsonMapper.builder().build();

  /** Written into every exported label; no personal data. */
  static final String NOTES =
      "Exported from a reviewed document (REV-4); invoiceNumber, orderNumber and"
          + " originalInvoiceRef are PII-stage tokens.";

  private static final int AMOUNT_SCALE = 2;
  private static final int DECIMAL_SCALE = 6;

  private ExportLabel() {}

  /** Why a reviewed extraction can't be written as a valid label. */
  static final class NotExportable extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String path;

    NotExportable(String path, String rule) {
      super(path + ": " + rule, null, false, false);
      this.path = path;
    }

    String path() {
      return path;
    }
  }

  /** The label JSON of {@code reviewed}; throws {@link NotExportable}. */
  static ObjectNode of(ReviewedExtraction reviewed) {
    ObjectNode label = JSON.createObjectNode();
    label.put("version", 1);
    label.put("sha256", reviewed.sha256());
    // The app can't tell generated documents from real ones; the owner flips this when needed.
    label.put("synthetic", false);
    label.put("pseudonymized", true);
    label.put("notes", NOTES);
    label.set("fields", fields(reviewed.extraction()));
    label.set("piiSpans", JSON.createArrayNode());
    return label;
  }

  private static ObjectNode fields(InvoiceExtraction e) {
    String at = "$.fields";
    ObjectNode f = JSON.createObjectNode();
    f.put("documentClass", required(e.documentClass(), at + ".documentClass").name());
    f.put("invoiceNumber", text(e.invoiceNumber()));
    f.put("invoiceDate", date(e.invoiceDate()));
    f.put("orderNumber", text(e.orderNumber()));
    f.put("orderDate", date(e.orderDate()));
    f.put("sellerName", text(e.sellerName()));
    f.put("sellerGstin", text(e.sellerGstin()));
    f.put("sellerPan", text(e.sellerPan()));
    f.put("sellerAddress", text(e.sellerAddress()));
    f.put("placeOfSupply", text(e.placeOfSupply()));
    f.put("placeOfDelivery", text(e.placeOfDelivery()));
    f.put("buyerState", text(e.buyerState()));
    Boolean reverseCharge = value(e.reverseCharge());
    if (reverseCharge == null) {
      f.putNull("reverseCharge");
    } else {
      f.put("reverseCharge", reverseCharge.booleanValue());
    }
    f.put("currency", required(e.currency(), at + ".currency").name());
    f.put("subtotal", amount(value(e.subtotal()), at + ".subtotal"));
    f.put("totalTax", amount(value(e.totalTax()), at + ".totalTax"));
    f.put("grandTotal", amount(required(e.grandTotal(), at + ".grandTotal"), at + ".grandTotal"));
    f.put("amountInWords", text(e.amountInWords()));
    f.put("originalInvoiceRef", text(e.originalInvoiceRef()));
    List<LineItem> lines = e.lineItems() == null ? List.of() : e.lineItems();
    if (lines.isEmpty()) {
      throw new NotExportable(at + ".lineItems", "must be a non-empty array");
    }
    ArrayNode items = f.putArray("lineItems");
    Set<Integer> lineNos = new HashSet<>();
    for (int i = 0; i < lines.size(); i++) {
      items.add(line(lines.get(i), at + ".lineItems[" + i + "]", lineNos));
    }
    return f;
  }

  private static ObjectNode line(LineItem line, String at, Set<Integer> lineNos) {
    ObjectNode l = JSON.createObjectNode();
    int lineNo = required(line.lineNo(), at + ".lineNo");
    if (lineNo < 1 || !lineNos.add(lineNo)) {
      throw new NotExportable(at + ".lineNo", "must be a unique whole number >= 1");
    }
    l.put("lineNo", lineNo);
    l.put("chargeType", required(line.chargeType(), at + ".chargeType").name());
    String description = text(line.description());
    if (description == null) {
      throw new NotExportable(at + ".description", "must be non-blank text");
    }
    l.put("description", description);
    l.put("hsnSac", text(line.hsnSac()));
    l.put("unitPrice", amount(value(line.unitPrice()), at + ".unitPrice"));
    l.put("quantity", decimal(value(line.quantity()), at + ".quantity"));
    l.put("discount", amount(value(line.discount()), at + ".discount"));
    l.put("netAmount", amount(value(line.netAmount()), at + ".netAmount"));
    ArrayNode taxes = l.putArray("taxLines");
    List<TaxLine> taxLines = line.taxLines() == null ? List.of() : line.taxLines();
    for (int i = 0; i < taxLines.size(); i++) {
      TaxLine tax = taxLines.get(i);
      String t = at + ".taxLines[" + i + "]";
      ObjectNode node = taxes.addObject();
      node.put("type", required(tax.type(), t + ".type").name());
      node.put("rate", decimal(required(tax.rate(), t + ".rate"), t + ".rate"));
      node.put("amount", amount(required(tax.amount(), t + ".amount"), t + ".amount"));
    }
    l.put("lineTotal", amount(required(line.lineTotal(), at + ".lineTotal"), at + ".lineTotal"));
    return l;
  }

  private static <T> T value(Field<T> field) {
    return field == null ? null : field.value();
  }

  private static <T> T required(Field<T> field, String path) {
    T value = value(field);
    if (value == null) {
      throw new NotExportable(path, "is required");
    }
    return value;
  }

  /** Blank text means the document shows no value: a label writes that as null. */
  private static String text(Field<String> field) {
    String value = value(field);
    return value == null || value.isBlank() ? null : value.strip();
  }

  private static String date(Field<java.time.LocalDate> field) {
    var value = value(field);
    return value == null ? null : value.toString();
  }

  static String amount(BigDecimal value, String path) {
    if (value == null) {
      return null;
    }
    if (value.stripTrailingZeros().scale() > AMOUNT_SCALE) {
      throw new NotExportable(path, "has more than two decimals");
    }
    return value.setScale(AMOUNT_SCALE).toPlainString();
  }

  static String decimal(BigDecimal value, String path) {
    if (value == null) {
      return null;
    }
    BigDecimal plain = value.stripTrailingZeros();
    if (plain.scale() > DECIMAL_SCALE) {
      throw new NotExportable(path, "has more than six decimals");
    }
    return (plain.scale() < 0 ? plain.setScale(0) : plain).toPlainString();
  }
}
