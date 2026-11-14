package dev.invoiceintel.eval.label;

import dev.invoiceintel.common.DocumentClass;
import dev.invoiceintel.common.extraction.ChargeType;
import dev.invoiceintel.common.extraction.Currency;
import dev.invoiceintel.common.extraction.TaxType;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Checks one label file against format version 1 (architecture.md §9.2, {@code
 * labels/label-v1.schema.json}), plus what JSON Schema can't express: real calendar dates, unique
 * line numbers, duplicate spans and the file name. Error messages name the JSON path and the rule,
 * never the value, because labels hold real personal data (.claude/rules/pii.md).
 */
public final class LabelValidator {

  /** Label format version this validator accepts. */
  public static final int VERSION = 1;

  static final List<String> TOP_KEYS =
      List.of("version", "sha256", "synthetic", "fields", "piiSpans");
  static final List<String> OPTIONAL_TOP_KEYS = List.of("notes", "pseudonymized");
  static final List<String> FIELD_KEYS =
      List.of(
          "documentClass",
          "invoiceNumber",
          "invoiceDate",
          "orderNumber",
          "orderDate",
          "sellerName",
          "sellerGstin",
          "sellerPan",
          "sellerAddress",
          "placeOfSupply",
          "placeOfDelivery",
          "buyerState",
          "reverseCharge",
          "currency",
          "subtotal",
          "totalTax",
          "grandTotal",
          "amountInWords",
          "originalInvoiceRef",
          "lineItems");
  static final List<String> LINE_KEYS =
      List.of(
          "lineNo",
          "chargeType",
          "description",
          "hsnSac",
          "unitPrice",
          "quantity",
          "discount",
          "netAmount",
          "taxLines",
          "lineTotal");
  static final List<String> TAX_KEYS = List.of("type", "rate", "amount");
  static final List<String> SPAN_KEYS = List.of("type", "page", "text");

  /**
   * PII types a span may have: the names of {@code dev.invoiceintel.pii.PiiType}, which {@code
   * eval} can't import (architecture.md §4.1). {@code LabelValidatorTest} keeps them in step with
   * the schema.
   */
  static final List<String> PII_TYPES =
      List.of(
          "PERSON",
          "ADDRESS",
          "PIN_CODE",
          "PHONE",
          "EMAIL",
          "PAN",
          "AADHAAR",
          "GSTIN",
          "ORDER_NUMBER",
          "INVOICE_NUMBER");

  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
  private static final Pattern AMOUNT = Pattern.compile("-?[0-9]+\\.[0-9]{2}");
  private static final Pattern DECIMAL = Pattern.compile("-?[0-9]+(?:\\.[0-9]{1,6})?");
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  private final List<String> errors = new ArrayList<>();

  private LabelValidator() {}

  /**
   * Errors in {@code label}, empty when it is valid.
   *
   * @param fileName the label's file name, which must be {@code <sha256>.json}
   */
  public static List<String> validate(JsonNode label, String fileName) {
    LabelValidator v = new LabelValidator();
    v.checkLabel(label, fileName);
    return List.copyOf(v.errors);
  }

  private void checkLabel(JsonNode label, String fileName) {
    if (!object(label, "$", TOP_KEYS, OPTIONAL_TOP_KEYS)) {
      return;
    }
    JsonNode version = label.get("version");
    if (version != null && !(version.isInt() && version.intValue() == VERSION)) {
      error("$.version", "must be " + VERSION);
    }
    JsonNode sha256 = label.get("sha256");
    if (sha256 != null) {
      if (!sha256.isString() || !SHA256.matcher(sha256.stringValue()).matches()) {
        error("$.sha256", "must be 64 lower-case hex characters");
      } else if (!fileName.equals(sha256.stringValue() + ".json")) {
        error("$.sha256", "must match the file name <sha256>.json");
      }
    }
    JsonNode synthetic = label.get("synthetic");
    if (synthetic != null && !synthetic.isBoolean()) {
      error("$.synthetic", "must be true or false");
    }
    JsonNode notes = label.get("notes");
    if (notes != null && !notes.isNull() && !notes.isString()) {
      error("$.notes", "must be text or null");
    }
    JsonNode fields = label.get("fields");
    if (fields != null) {
      checkFields(fields);
    }
    JsonNode pseudonymized = label.get("pseudonymized");
    if (pseudonymized != null && !pseudonymized.isBoolean()) {
      error("$.pseudonymized", "must be true or false");
    }
    JsonNode spans = label.get("piiSpans");
    if (spans != null) {
      checkSpans(spans);
      // An exported label has no PII text to hold (REV-4, ADR-31).
      if (isPseudonymized(label) && spans.isArray() && !spans.isEmpty()) {
        error("$.piiSpans", "must be empty when pseudonymized is true");
      }
    }
  }

  /**
   * Whether the label was exported from a reviewed document (REV-4, ADR-31): its token fields hold
   * the PII stage's tokens and it has no PII spans. A missing key means {@code false}.
   */
  public static boolean isPseudonymized(JsonNode label) {
    JsonNode flag = label.get("pseudonymized");
    return flag != null && flag.isBoolean() && flag.booleanValue();
  }

  private void checkFields(JsonNode fields) {
    String at = "$.fields";
    if (!object(fields, at, FIELD_KEYS, List.of())) {
      return;
    }
    enumValue(
        fields.get("documentClass"), at + ".documentClass", names(DocumentClass.values()), false);
    for (String key :
        List.of(
            "invoiceNumber",
            "orderNumber",
            "sellerName",
            "sellerGstin",
            "sellerPan",
            "sellerAddress",
            "placeOfSupply",
            "placeOfDelivery",
            "buyerState",
            "amountInWords",
            "originalInvoiceRef")) {
      text(fields.get(key), at + "." + key, true);
    }
    date(fields.get("invoiceDate"), at + ".invoiceDate");
    date(fields.get("orderDate"), at + ".orderDate");
    JsonNode reverseCharge = fields.get("reverseCharge");
    if (reverseCharge != null && !reverseCharge.isNull() && !reverseCharge.isBoolean()) {
      error(at + ".reverseCharge", "must be true, false or null");
    }
    enumValue(fields.get("currency"), at + ".currency", names(Currency.values()), false);
    amount(fields.get("subtotal"), at + ".subtotal", true);
    amount(fields.get("totalTax"), at + ".totalTax", true);
    amount(fields.get("grandTotal"), at + ".grandTotal", false);
    JsonNode lines = fields.get("lineItems");
    if (lines == null) {
      return;
    }
    if (!lines.isArray() || lines.isEmpty()) {
      error(at + ".lineItems", "must be a non-empty array");
      return;
    }
    Set<Integer> lineNos = new HashSet<>();
    for (int i = 0; i < lines.size(); i++) {
      checkLine(lines.get(i), at + ".lineItems[" + i + "]", lineNos);
    }
  }

  private void checkLine(JsonNode line, String at, Set<Integer> lineNos) {
    if (!object(line, at, LINE_KEYS, List.of())) {
      return;
    }
    JsonNode lineNo = line.get("lineNo");
    if (!lineNo.isInt() || lineNo.intValue() < 1) {
      error(at + ".lineNo", "must be a whole number >= 1");
    } else if (!lineNos.add(lineNo.intValue())) {
      error(at + ".lineNo", "is used by an earlier line");
    }
    enumValue(line.get("chargeType"), at + ".chargeType", names(ChargeType.values()), false);
    text(line.get("description"), at + ".description", false);
    text(line.get("hsnSac"), at + ".hsnSac", true);
    amount(line.get("unitPrice"), at + ".unitPrice", true);
    decimal(line.get("quantity"), at + ".quantity", true);
    amount(line.get("discount"), at + ".discount", true);
    amount(line.get("netAmount"), at + ".netAmount", true);
    amount(line.get("lineTotal"), at + ".lineTotal", false);
    JsonNode taxLines = line.get("taxLines");
    if (!taxLines.isArray()) {
      error(at + ".taxLines", "must be an array (empty when the line shows no tax)");
      return;
    }
    for (int i = 0; i < taxLines.size(); i++) {
      String tax = at + ".taxLines[" + i + "]";
      JsonNode taxLine = taxLines.get(i);
      if (object(taxLine, tax, TAX_KEYS, List.of())) {
        enumValue(taxLine.get("type"), tax + ".type", names(TaxType.values()), false);
        decimal(taxLine.get("rate"), tax + ".rate", false);
        amount(taxLine.get("amount"), tax + ".amount", false);
      }
    }
  }

  private void checkSpans(JsonNode spans) {
    if (!spans.isArray()) {
      error("$.piiSpans", "must be an array");
      return;
    }
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < spans.size(); i++) {
      String at = "$.piiSpans[" + i + "]";
      JsonNode span = spans.get(i);
      if (!object(span, at, SPAN_KEYS, List.of())) {
        continue;
      }
      boolean typeOk = enumValue(span.get("type"), at + ".type", PII_TYPES, false);
      JsonNode page = span.get("page");
      boolean pageOk = page.isInt() && page.intValue() >= 1;
      if (!pageOk) {
        error(at + ".page", "must be a whole number >= 1");
      }
      boolean textOk = text(span.get("text"), at + ".text", false);
      if (typeOk && pageOk && textOk) {
        String key =
            span.get("type").stringValue()
                + "|"
                + page.intValue()
                + "|"
                + normalize(span.get("text").stringValue());
        if (!seen.add(key)) {
          error(at, "repeats an earlier span (same type, page and text)");
        }
      }
    }
  }

  /** How the eval harness compares span text: whitespace collapsed, case ignored. */
  static String normalize(String text) {
    return WHITESPACE.matcher(text.strip()).replaceAll(" ").toLowerCase(Locale.ROOT);
  }

  private boolean object(JsonNode node, String at, List<String> required, List<String> optional) {
    if (!node.isObject()) {
      error(at, "must be an object");
      return false;
    }
    Set<String> allowed = new LinkedHashSet<>(required);
    allowed.addAll(optional);
    for (String key : node.propertyNames()) {
      if (!allowed.contains(key)) {
        error(at + "." + key, "is not a known key");
      }
    }
    boolean complete = true;
    for (String key : required) {
      if (!node.has(key)) {
        error(at + "." + key, "is missing (use null when the document doesn't show it)");
        complete = false;
      }
    }
    return complete;
  }

  private boolean text(JsonNode node, String at, boolean nullable) {
    if (node == null || (nullable && node.isNull())) {
      return node != null;
    }
    if (!node.isString() || node.stringValue().isBlank()) {
      error(at, nullable ? "must be non-blank text or null" : "must be non-blank text");
      return false;
    }
    return true;
  }

  private void date(JsonNode node, String at) {
    if (node == null || node.isNull()) {
      return;
    }
    if (node.isString() && DATE.matcher(node.stringValue()).matches()) {
      try {
        LocalDate.parse(node.stringValue());
        return;
      } catch (DateTimeParseException e) {
        // falls through to the error below
      }
    }
    error(at, "must be a calendar date YYYY-MM-DD or null");
  }

  private void amount(JsonNode node, String at, boolean nullable) {
    matches(
        node, at, nullable, AMOUNT, "an amount string with exactly two decimals, e.g. \"1234.50\"");
  }

  private void decimal(JsonNode node, String at, boolean nullable) {
    matches(node, at, nullable, DECIMAL, "a decimal string, e.g. \"2\" or \"0.5\"");
  }

  private void matches(JsonNode node, String at, boolean nullable, Pattern pattern, String what) {
    if (node == null || (nullable && node.isNull())) {
      return;
    }
    if (!node.isString() || !pattern.matcher(node.stringValue()).matches()) {
      error(at, "must be " + what + (nullable ? " or null" : ""));
    }
  }

  private boolean enumValue(JsonNode node, String at, List<String> allowed, boolean nullable) {
    if (node == null || (nullable && node.isNull())) {
      return node != null;
    }
    if (!node.isString() || !allowed.contains(node.stringValue())) {
      error(at, "must be one of " + allowed);
      return false;
    }
    return true;
  }

  private static List<String> names(Enum<?>[] values) {
    return Arrays.stream(values).map(Enum::name).toList();
  }

  private void error(String at, String rule) {
    errors.add(at + ": " + rule);
  }
}
