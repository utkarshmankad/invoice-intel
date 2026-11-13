package dev.invoiceintel.extract;

import dev.invoiceintel.common.extraction.FieldSource;
import dev.invoiceintel.common.extraction.InvoiceExtraction;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Applies a reviewer's field correction to an extraction (REV-3, architecture.md §6.8, ADR-30).
 * Fields are named by the validator's paths ({@code grandTotal}, {@code
 * lineItems[0].taxLines[1].rate}); a corrected field gets {@code source = HUMAN}, confidence 1 and
 * keeps its evidence. The extraction's JSON shape ({@link ExtractionStore}) turns the text into the
 * field's type, so a value that doesn't parse is rejected. Only fields the extraction has can be
 * corrected, and fields that hold PII tokens can't (specs.md §6): a reviewer only sees masked
 * images, and a typed real value would be stored in clear.
 */
public final class FieldCorrections {

  /** Fields whose values are PII tokens, never corrected by hand. */
  public static final Set<String> TOKEN_FIELDS =
      Set.of("invoiceNumber", "orderNumber", "originalInvoiceRef");

  /** Longest accepted corrected value. */
  public static final int MAX_VALUE_LENGTH = 500;

  private static final int MAX_PATH_LENGTH = 100;

  private static final JsonMapper JSON =
      JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

  private FieldCorrections() {}

  /**
   * The field's current value as the review form shows it, or empty if the extraction has no field
   * at {@code path}.
   */
  public static Optional<String> value(InvoiceExtraction extraction, String path) {
    Objects.requireNonNull(extraction, "extraction");
    return field(JSON.valueToTree(extraction), path).map(f -> text(f.get("value")));
  }

  /**
   * {@code extraction} with the field at {@code path} set to {@code value}.
   *
   * @throws InvalidCorrectionException if the path names no field of the extraction, a token field,
   *     or the value is blank, too long or doesn't parse as the field's type; the message names the
   *     path only, never the value (PII-8)
   */
  public static InvoiceExtraction apply(InvoiceExtraction extraction, String path, String value) {
    Objects.requireNonNull(extraction, "extraction");
    if (path == null || TOKEN_FIELDS.contains(path)) {
      throw new InvalidCorrectionException("field can't be corrected: " + safe(path));
    }
    if (value == null || value.isBlank() || value.length() > MAX_VALUE_LENGTH) {
      throw new InvalidCorrectionException("missing or too long value for " + path);
    }
    JsonNode tree = JSON.valueToTree(extraction);
    ObjectNode field =
        field(tree, path)
            .orElseThrow(() -> new InvalidCorrectionException("no such field: " + safe(path)));
    field.set("value", parsed(field.get("value"), value.strip(), path));
    field.put("confidence", 1.0);
    field.put("source", FieldSource.HUMAN.name());
    try {
      return JSON.treeToValue(tree, InvoiceExtraction.class);
    } catch (JacksonException e) {
      throw new InvalidCorrectionException("value doesn't fit field " + path);
    }
  }

  private static JsonNode parsed(JsonNode current, String value, String path) {
    var nodes = JsonNodeFactory.instance;
    if (current.isNumber()) {
      try {
        // lineNo is the schema's only integer; every other number is a BigDecimal.
        return path.endsWith("lineNo")
            ? nodes.numberNode(Integer.parseInt(value))
            : nodes.numberNode(new BigDecimal(value));
      } catch (NumberFormatException e) {
        throw new InvalidCorrectionException("not a number for field " + path);
      }
    }
    if (current.isBoolean()) {
      return switch (value) {
        case "true" -> nodes.booleanNode(true);
        case "false" -> nodes.booleanNode(false);
        default -> throw new InvalidCorrectionException("not true/false for field " + path);
      };
    }
    return nodes.stringNode(value);
  }

  private static Optional<ObjectNode> field(JsonNode tree, String path) {
    if (!wellFormed(path)) {
      return Optional.empty();
    }
    JsonNode node = tree;
    for (String segment : path.split("\\.", -1)) {
      if (node == null || !node.isObject()) {
        return Optional.empty();
      }
      int bracket = segment.indexOf('[');
      node = node.get(bracket < 0 ? segment : segment.substring(0, bracket));
      if (bracket >= 0) {
        int index = Integer.parseInt(segment.substring(bracket + 1, segment.length() - 1));
        node = node != null && node.isArray() ? node.get(index) : null;
      }
    }
    return node instanceof ObjectNode object && object.has("value") && object.has("source")
        ? Optional.of(object)
        : Optional.empty();
  }

  private static String text(JsonNode value) {
    if (value == null || value.isNull()) {
      return "";
    }
    return value.isNumber() ? value.decimalValue().toPlainString() : value.asString();
  }

  private static String safe(String path) {
    return wellFormed(path) ? path : "(invalid)";
  }

  /**
   * Whether {@code path} is dot-separated segments of ASCII letters, each optionally followed by an
   * index of 1–3 digits in brackets. Parsed by hand: a regex for it trips SpotBugs' REDOS check.
   */
  static boolean wellFormed(String path) {
    if (path == null || path.isEmpty() || path.length() > MAX_PATH_LENGTH) {
      return false;
    }
    for (String segment : path.split("\\.", -1)) {
      int i = 0;
      while (i < segment.length() && isAsciiLetter(segment.charAt(i))) {
        i++;
      }
      if (i == 0) {
        return false;
      }
      if (i == segment.length()) {
        continue;
      }
      int digits = segment.length() - i - 2;
      if (segment.charAt(i) != '['
          || segment.charAt(segment.length() - 1) != ']'
          || digits < 1
          || digits > 3
          || !segment
              .substring(i + 1, segment.length() - 1)
              .chars()
              .allMatch(c -> c >= '0' && c <= '9')) {
        return false;
      }
    }
    return true;
  }

  private static boolean isAsciiLetter(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
  }

  /** A correction names no correctable field or has a value the field can't hold. */
  public static final class InvalidCorrectionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    InvalidCorrectionException(String message) {
      super(message);
    }
  }
}
