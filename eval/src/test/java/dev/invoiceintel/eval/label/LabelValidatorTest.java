package dev.invoiceintel.eval.label;

import static dev.invoiceintel.eval.label.LabelFixtures.BILL_OF_SUPPLY;
import static dev.invoiceintel.eval.label.LabelFixtures.JSON;
import static dev.invoiceintel.eval.label.LabelFixtures.TAX_INVOICE;
import static org.assertj.core.api.Assertions.assertThat;

import dev.invoiceintel.common.DocumentClass;
import dev.invoiceintel.common.extraction.ChargeType;
import dev.invoiceintel.common.extraction.Currency;
import dev.invoiceintel.common.extraction.TaxType;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class LabelValidatorTest {

  private static final String MARKER = "Unique Marker Value 4711";

  @ParameterizedTest
  @FieldSource("dev.invoiceintel.eval.label.LabelFixtures#ALL")
  void validate_syntheticFixture_hasNoErrors(String name) {
    assertThat(LabelValidator.validate(LabelFixtures.tree(name), name)).isEmpty();
  }

  static Stream<Arguments> invalidLabels() {
    return Stream.of(
        broken("version 2", l -> l.put("version", 2), "$.version: must be 1"),
        broken(
            "upper-case hash",
            l -> l.put("sha256", l.get("sha256").stringValue().toUpperCase()),
            "$.sha256: must be 64 lower-case hex characters"),
        broken(
            "hash of another file",
            l -> l.put("sha256", "0".repeat(64)),
            "$.sha256: must match the file name"),
        broken(
            "synthetic as text",
            l -> l.put("synthetic", "yes"),
            "$.synthetic: must be true or false"),
        broken(
            "unknown top-level key", l -> l.put("labeller", "x"), "$.labeller: is not a known key"),
        broken(
            "field left out",
            l -> fields(l).remove("orderDate"),
            "$.fields.orderDate: is missing (use null"),
        broken(
            "unknown field",
            l -> fields(l).put("buyerName", MARKER),
            "$.fields.buyerName: is not a known key"),
        broken(
            "unknown document class",
            l -> fields(l).put("documentClass", "RECEIPT"),
            "$.fields.documentClass: must be one of"),
        broken(
            "blank invoice number",
            l -> fields(l).put("invoiceNumber", " "),
            "$.fields.invoiceNumber: must be non-blank text or null"),
        broken(
            "day-first date",
            l -> fields(l).put("invoiceDate", "15-03-2024"),
            "$.fields.invoiceDate: must be a calendar date"),
        broken(
            "impossible date",
            l -> fields(l).put("orderDate", "2024-02-30"),
            "$.fields.orderDate: must be a calendar date"),
        broken(
            "amount as number",
            l -> fields(l).put("grandTotal", 1227.2),
            "$.fields.grandTotal: must be an amount string"),
        broken(
            "amount with one decimal",
            l -> fields(l).put("subtotal", "1040.0"),
            "$.fields.subtotal: must be an amount string with exactly two decimals"),
        broken(
            "grand total null",
            l -> fields(l).putNull("grandTotal"),
            "$.fields.grandTotal: must be an amount string"),
        broken(
            "other currency",
            l -> fields(l).put("currency", "USD"),
            "$.fields.currency: must be one of [INR]"),
        broken(
            "reverse charge as text",
            l -> fields(l).put("reverseCharge", "No"),
            "$.fields.reverseCharge: must be true, false or null"),
        broken(
            "no line items",
            l -> fields(l).set("lineItems", JSON.createArrayNode()),
            "$.fields.lineItems: must be a non-empty array"),
        broken(
            "line number zero",
            l -> line(l, 0).put("lineNo", 0),
            "$.fields.lineItems[0].lineNo: must be a whole number >= 1"),
        broken(
            "repeated line number",
            l -> line(l, 1).put("lineNo", 1),
            "$.fields.lineItems[1].lineNo: is used by an earlier line"),
        broken(
            "unknown charge type",
            l -> line(l, 0).put("chargeType", "GIFT_WRAP"),
            "$.fields.lineItems[0].chargeType: must be one of"),
        broken(
            "quantity with comma",
            l -> line(l, 0).put("quantity", "1,000"),
            "$.fields.lineItems[0].quantity: must be a decimal string"),
        broken(
            "tax lines null",
            l -> line(l, 0).putNull("taxLines"),
            "$.fields.lineItems[0].taxLines: must be an array"),
        broken(
            "unknown tax type",
            l -> tax(l).put("type", "GST"),
            "$.fields.lineItems[0].taxLines[0].type: must be one of"),
        broken(
            "rate with percent sign",
            l -> tax(l).put("rate", "18%"),
            "$.fields.lineItems[0].taxLines[0].rate: must be a decimal string"),
        broken(
            "unknown PII type",
            l -> span(l, 0).put("type", "NAME"),
            "$.piiSpans[0].type: must be one of"),
        broken(
            "page zero",
            l -> span(l, 0).put("page", 0),
            "$.piiSpans[0].page: must be a whole number >= 1"),
        broken(
            "blank span text",
            l -> span(l, 0).put("text", ""),
            "$.piiSpans[0].text: must be non-blank text"),
        broken(
            "repeated span",
            l -> spans(l).add(span(l, 0).deepCopy().put("text", " asha   SYNTHETIC ")),
            "$.piiSpans[5]: repeats an earlier span"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidLabels")
  void validate_brokenLabel_namesPathAndRule(
      String description, Consumer<ObjectNode> breakIt, String expected) {
    ObjectNode label = LabelFixtures.tree(TAX_INVOICE);
    breakIt.accept(label);

    assertThat(LabelValidator.validate(label, TAX_INVOICE))
        .anySatisfy(e -> assertThat(e).startsWith(expected));
  }

  @Test
  void validate_notAnObject_reportsRoot() {
    assertThat(LabelValidator.validate(JSON.readTree("[]"), TAX_INVOICE))
        .containsExactly("$: must be an object");
  }

  @Test
  void validate_wrongValues_neverQuotesThem() {
    ObjectNode label = LabelFixtures.tree(TAX_INVOICE);
    fields(label).put("invoiceDate", MARKER).put("grandTotal", MARKER).put("buyerName", MARKER);
    span(label, 0).put("type", MARKER);

    List<String> errors = LabelValidator.validate(label, TAX_INVOICE);

    assertThat(errors).hasSize(4).noneMatch(e -> e.contains(MARKER));
  }

  @Test
  void validate_billOfSupply_allowsNullsAndNoTax() {
    ObjectNode label = LabelFixtures.tree(BILL_OF_SUPPLY);

    assertThat(LabelValidator.validate(label, BILL_OF_SUPPLY)).isEmpty();
    assertThat(line(label, 0).get("taxLines")).isEmpty();
  }

  @Test
  void normalize_collapsesWhitespaceAndCase() {
    assertThat(LabelValidator.normalize("  12 Example\nLane,\t MUMBAI "))
        .isEqualTo("12 example lane, mumbai");
  }

  @Test
  void schema_keysAndEnums_matchTheValidator() throws IOException {
    JsonNode schema;
    try (InputStream in = getClass().getResourceAsStream("/labels/label-v1.schema.json")) {
      schema = JSON.readTree(in);
    }
    JsonNode defs = schema.get("$defs");

    assertThat(strings(schema.get("required"))).containsExactlyElementsOf(LabelValidator.TOP_KEYS);
    assertThat(names(schema.get("properties")))
        .containsExactlyInAnyOrderElementsOf(
            concat(LabelValidator.TOP_KEYS, LabelValidator.OPTIONAL_TOP_KEYS));
    assertThat(strings(defs.at("/fields/required")))
        .containsExactlyElementsOf(LabelValidator.FIELD_KEYS);
    assertThat(names(defs.at("/fields/properties")))
        .containsExactlyElementsOf(LabelValidator.FIELD_KEYS);
    assertThat(strings(defs.at("/lineItem/required")))
        .containsExactlyElementsOf(LabelValidator.LINE_KEYS);
    assertThat(strings(defs.at("/taxLine/required")))
        .containsExactlyElementsOf(LabelValidator.TAX_KEYS);
    assertThat(strings(defs.at("/piiSpan/required")))
        .containsExactlyElementsOf(LabelValidator.SPAN_KEYS);
    assertThat(strings(defs.at("/fields/properties/documentClass/enum")))
        .containsExactlyElementsOf(enumNames(DocumentClass.values()));
    assertThat(strings(defs.at("/fields/properties/currency/enum")))
        .containsExactlyElementsOf(enumNames(Currency.values()));
    assertThat(strings(defs.at("/lineItem/properties/chargeType/enum")))
        .containsExactlyElementsOf(enumNames(ChargeType.values()));
    assertThat(strings(defs.at("/taxLine/properties/type/enum")))
        .containsExactlyElementsOf(enumNames(TaxType.values()));
    assertThat(strings(defs.at("/piiSpan/properties/type/enum")))
        .containsExactlyElementsOf(LabelValidator.PII_TYPES);
  }

  private static Arguments broken(
      String description, Consumer<ObjectNode> breakIt, String expected) {
    return Arguments.of(description, breakIt, expected);
  }

  private static ObjectNode fields(ObjectNode label) {
    return (ObjectNode) label.get("fields");
  }

  private static ObjectNode line(ObjectNode label, int i) {
    return (ObjectNode) fields(label).get("lineItems").get(i);
  }

  private static ObjectNode tax(ObjectNode label) {
    return (ObjectNode) line(label, 0).get("taxLines").get(0);
  }

  private static ArrayNode spans(ObjectNode label) {
    return (ArrayNode) label.get("piiSpans");
  }

  private static ObjectNode span(ObjectNode label, int i) {
    return (ObjectNode) spans(label).get(i);
  }

  private static List<String> strings(JsonNode array) {
    return StreamSupport.stream(array.spliterator(), false).map(JsonNode::stringValue).toList();
  }

  private static List<String> names(JsonNode object) {
    return List.copyOf(object.propertyNames());
  }

  private static List<String> enumNames(Enum<?>[] values) {
    return Arrays.stream(values).map(Enum::name).toList();
  }

  private static List<String> concat(List<String> a, List<String> b) {
    return Stream.concat(a.stream(), b.stream()).toList();
  }
}
