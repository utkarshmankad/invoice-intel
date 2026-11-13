package dev.invoiceintel.app.ui;

import dev.invoiceintel.common.extraction.BoundingBox;
import dev.invoiceintel.common.extraction.Evidence;
import dev.invoiceintel.common.extraction.Field;
import dev.invoiceintel.common.extraction.InvoiceExtraction;
import dev.invoiceintel.common.extraction.LineItem;
import dev.invoiceintel.common.extraction.TaxLine;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns an extraction into the rows of the document page's field form and the evidence boxes of its
 * overlay (REV-2, architecture.md §6.8). Field paths are the ones the validator and the label
 * format use ({@code invoiceNumber}, {@code lineItems[0].taxLines[1].rate}); they also name the
 * field in the REV-3 corrections. Values are the stored, pseudonymized ones (specs.md §6).
 */
final class ReviewDocumentView {

  private ReviewDocumentView() {}

  /** One field of the form; absent optional fields are left out. */
  record FieldRow(
      String path, String htmlId, String value, String confidence, String source, Box box) {

    /** Whether the field has an evidence box to highlight. */
    public boolean hasBox() {
      return box != null;
    }
  }

  /**
   * An evidence box in the text layer page's unit, the overlay's SVG coordinate space.
   *
   * @param x left edge, formatted for an SVG attribute
   */
  record Box(int page, String path, String x, String y, String w, String h) {}

  /** The form rows of {@code extraction} in schema order; empty for {@code null}. */
  static List<FieldRow> rows(InvoiceExtraction extraction) {
    var rows = new ArrayList<FieldRow>();
    if (extraction == null) {
      return rows;
    }
    add(rows, "documentClass", extraction.documentClass());
    add(rows, "invoiceNumber", extraction.invoiceNumber());
    add(rows, "invoiceDate", extraction.invoiceDate());
    add(rows, "orderNumber", extraction.orderNumber());
    add(rows, "orderDate", extraction.orderDate());
    add(rows, "sellerName", extraction.sellerName());
    add(rows, "sellerGstin", extraction.sellerGstin());
    add(rows, "sellerPan", extraction.sellerPan());
    add(rows, "sellerAddress", extraction.sellerAddress());
    add(rows, "placeOfSupply", extraction.placeOfSupply());
    add(rows, "placeOfDelivery", extraction.placeOfDelivery());
    add(rows, "buyerState", extraction.buyerState());
    add(rows, "reverseCharge", extraction.reverseCharge());
    add(rows, "currency", extraction.currency());
    add(rows, "subtotal", extraction.subtotal());
    add(rows, "totalTax", extraction.totalTax());
    add(rows, "grandTotal", extraction.grandTotal());
    add(rows, "amountInWords", extraction.amountInWords());
    add(rows, "originalInvoiceRef", extraction.originalInvoiceRef());
    List<LineItem> items = extraction.lineItems() == null ? List.of() : extraction.lineItems();
    for (int i = 0; i < items.size(); i++) {
      LineItem item = items.get(i);
      String p = "lineItems[" + i + "].";
      add(rows, p + "lineNo", item.lineNo());
      add(rows, p + "chargeType", item.chargeType());
      add(rows, p + "description", item.description());
      add(rows, p + "hsnSac", item.hsnSac());
      add(rows, p + "unitPrice", item.unitPrice());
      add(rows, p + "quantity", item.quantity());
      add(rows, p + "discount", item.discount());
      add(rows, p + "netAmount", item.netAmount());
      List<TaxLine> taxes = item.taxLines() == null ? List.of() : item.taxLines();
      for (int t = 0; t < taxes.size(); t++) {
        String tp = p + "taxLines[" + t + "].";
        add(rows, tp + "type", taxes.get(t).type());
        add(rows, tp + "rate", taxes.get(t).rate());
        add(rows, tp + "amount", taxes.get(t).amount());
      }
      add(rows, p + "lineTotal", item.lineTotal());
    }
    return rows;
  }

  /** The evidence boxes of {@code rows} on page {@code pageNo}. */
  static List<Box> boxes(List<FieldRow> rows, int pageNo) {
    return rows.stream()
        .filter(FieldRow::hasBox)
        .map(FieldRow::box)
        .filter(b -> b.page() == pageNo)
        .toList();
  }

  /** A number for an SVG attribute: plain notation, no exponent, no trailing zeros. */
  static String svgNumber(double value) {
    return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
  }

  private static void add(List<FieldRow> rows, String path, Field<?> field) {
    if (field == null) {
      return;
    }
    rows.add(
        new FieldRow(
            path,
            "field-" + path.replaceAll("[^A-Za-z0-9]+", "-"),
            value(field.value()),
            String.format(Locale.ROOT, "%.2f", field.confidence()),
            field.source() == null ? "" : field.source().name(),
            box(path, field.evidence())));
  }

  private static String value(Object value) {
    return switch (value) {
      case null -> "";
      case BigDecimal amount -> amount.toPlainString();
      case Enum<?> constant -> constant.name();
      default -> value.toString();
    };
  }

  private static Box box(String path, Evidence evidence) {
    if (evidence == null || evidence.box() == null) {
      return null;
    }
    BoundingBox b = evidence.box();
    return new Box(
        evidence.page(),
        path,
        svgNumber(b.x()),
        svgNumber(b.y()),
        svgNumber(b.w()),
        svgNumber(b.h()));
  }
}
