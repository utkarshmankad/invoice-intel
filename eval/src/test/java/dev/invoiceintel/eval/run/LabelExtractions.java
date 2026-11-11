package dev.invoiceintel.eval.run;

import dev.invoiceintel.common.DocumentClass;
import dev.invoiceintel.common.extraction.ChargeType;
import dev.invoiceintel.common.extraction.Currency;
import dev.invoiceintel.common.extraction.Field;
import dev.invoiceintel.common.extraction.InvoiceExtraction;
import dev.invoiceintel.common.extraction.LineItem;
import dev.invoiceintel.common.extraction.TaxLine;
import dev.invoiceintel.common.extraction.TaxType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;

/** Builds the extraction a perfect pipeline would return for a synthetic label (tests only). */
public final class LabelExtractions {

  private LabelExtractions() {}

  /** Every label value as an LLM field with confidence 0.99. */
  public static InvoiceExtraction from(JsonNode label) {
    JsonNode f = label.get("fields");
    List<LineItem> items = new ArrayList<>();
    for (JsonNode item : f.get("lineItems")) {
      List<TaxLine> taxes = new ArrayList<>();
      for (JsonNode tax : item.get("taxLines")) {
        taxes.add(
            new TaxLine(
                field(tax, "type", TaxType::valueOf),
                field(tax, "rate", BigDecimal::new),
                field(tax, "amount", BigDecimal::new)));
      }
      items.add(
          new LineItem(
              Field.llm(item.get("lineNo").intValue(), 0.99),
              field(item, "chargeType", ChargeType::valueOf),
              field(item, "description", s -> s),
              field(item, "hsnSac", s -> s),
              field(item, "unitPrice", BigDecimal::new),
              field(item, "quantity", BigDecimal::new),
              field(item, "discount", BigDecimal::new),
              field(item, "netAmount", BigDecimal::new),
              taxes,
              field(item, "lineTotal", BigDecimal::new)));
    }
    JsonNode reverse = f.get("reverseCharge");
    return new InvoiceExtraction(
        field(f, "documentClass", DocumentClass::valueOf),
        field(f, "invoiceNumber", s -> s),
        field(f, "invoiceDate", LocalDate::parse),
        field(f, "orderNumber", s -> s),
        field(f, "orderDate", LocalDate::parse),
        field(f, "sellerName", s -> s),
        field(f, "sellerGstin", s -> s),
        field(f, "sellerPan", s -> s),
        field(f, "sellerAddress", s -> s),
        field(f, "placeOfSupply", s -> s),
        field(f, "placeOfDelivery", s -> s),
        field(f, "buyerState", s -> s),
        reverse.isNull() ? null : Field.llm(reverse.booleanValue(), 0.99),
        field(f, "currency", Currency::valueOf),
        field(f, "subtotal", BigDecimal::new),
        field(f, "totalTax", BigDecimal::new),
        field(f, "grandTotal", BigDecimal::new),
        field(f, "amountInWords", s -> s),
        field(f, "originalInvoiceRef", s -> s),
        items);
  }

  private static <T> Field<T> field(JsonNode node, String key, Function<String, T> parse) {
    JsonNode value = node.get(key);
    return value == null || value.isNull()
        ? null
        : Field.llm(parse.apply(value.stringValue()), 0.99);
  }
}
