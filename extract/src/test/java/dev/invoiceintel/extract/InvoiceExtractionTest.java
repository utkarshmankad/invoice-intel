package dev.invoiceintel.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.invoiceintel.common.DocumentClass;
import dev.invoiceintel.common.fixtures.FakeIds;
import dev.invoiceintel.common.fixtures.GstState;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

class InvoiceExtractionTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String SELLER_GSTIN =
      FakeIds.gstin(GstState.MAHARASHTRA, FakeIds.pan(new Random(7), 'C', 'S'), '1');

  private static ValidatorFactory factory;
  private static Validator validator;

  @BeforeAll
  static void setUpValidator() {
    factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @AfterAll
  static void closeValidator() {
    factory.close();
  }

  @Test
  void json_roundTrip_preservesEveryField() {
    InvoiceExtraction extraction = sample();

    String json = JSON.writeValueAsString(extraction);
    InvoiceExtraction parsed = JSON.readValue(json, InvoiceExtraction.class);

    assertThat(parsed).isEqualTo(extraction);
    assertThat(parsed.grandTotal().value()).hasToString("1180.00");
    assertThat(parsed.lineItems().getFirst().taxLines().getFirst().type().value())
        .isEqualTo(TaxType.CGST);
  }

  @Test
  void json_datesAndAmounts_areWrittenAsIsoStringsAndExactDecimals() {
    String json = JSON.writeValueAsString(sample());

    assertThat(json)
        .contains("\"invoiceDate\":{\"value\":\"2025-03-14\"")
        .contains("\"grandTotal\":{\"value\":1180.00");
  }

  @Test
  void json_handWrittenModelResponse_parsesAndValidates() {
    // Shaped like a stubbed LLM response (testing.md: written from the schema, not a real run).
    String json =
        """
        {
          "documentClass": {"value": "TAX_INVOICE", "confidence": 0.95, "source": "LLM"},
          "invoiceNumber": {"value": "INVNO_TOKEN_01", "confidence": 0.9, "source": "LLM",
                            "evidence": {"page": 1, "lineRefs": ["P1L3"]}},
          "invoiceDate": {"value": "2025-03-14", "confidence": 0.9, "source": "LLM"},
          "orderNumber": {"value": "ORDNO_TOKEN_01", "confidence": 0.9, "source": "LLM"},
          "sellerName": {"value": "Example Traders", "confidence": 0.8, "source": "LLM"},
          "buyerState": {"value": "Maharashtra", "confidence": 0.8, "source": "LLM"},
          "currency": {"value": "INR", "confidence": 1.0, "source": "RULE"},
          "subtotal": {"value": 100.00, "confidence": 0.9, "source": "LLM"},
          "totalTax": {"value": 18.00, "confidence": 0.9, "source": "LLM"},
          "grandTotal": {"value": 118.00, "confidence": 0.9, "source": "LLM"},
          "lineItems": [{
            "lineNo": {"value": 1, "confidence": 1.0, "source": "LLM"},
            "chargeType": {"value": "PRODUCT", "confidence": 0.9, "source": "LLM"},
            "description": {"value": "Steel water bottle", "confidence": 0.9, "source": "LLM"},
            "unitPrice": {"value": 100.00, "confidence": 0.9, "source": "LLM"},
            "quantity": {"value": 1, "confidence": 0.9, "source": "LLM"},
            "netAmount": {"value": 100.00, "confidence": 0.9, "source": "LLM"},
            "taxLines": [{
              "type": {"value": "IGST", "confidence": 0.9, "source": "LLM"},
              "rate": {"value": 18, "confidence": 0.9, "source": "LLM"},
              "amount": {"value": 18.00, "confidence": 0.9, "source": "LLM"}
            }],
            "lineTotal": {"value": 118.00, "confidence": 0.9, "source": "LLM"}
          }]
        }
        """;

    InvoiceExtraction parsed = JSON.readValue(json, InvoiceExtraction.class);

    assertThat(validator.validate(parsed)).isEmpty();
    assertThat(parsed.orderDate()).isNull();
    assertThat(parsed.invoiceNumber().evidence().lineRefs()).containsExactly("P1L3");
    assertThat(parsed.invoiceNumber().evidence().box()).isNull();
    assertThat(parsed.invoiceDate().value()).isEqualTo(LocalDate.of(2025, 3, 14));
    assertThat(parsed.subtotal().value()).isEqualByComparingTo("100");
  }

  @Test
  void json_unknownEnumValue_failsToParse() {
    String json = "{\"value\": \"GST\", \"confidence\": 0.9, \"source\": \"LLM\"}";

    assertThatThrownBy(() -> JSON.readValue(json, TaxLineTypeField.class))
        .isInstanceOf(JacksonException.class);
  }

  @Test
  void validate_completeExtraction_hasNoViolations() {
    assertThat(validator.validate(sample())).isEmpty();
  }

  @Test
  void validate_missingRequiredField_reportsItsPath() {
    InvoiceExtraction extraction = withComponent(sample(), "orderNumber", null);

    assertThat(paths(validator.validate(extraction))).containsExactly("orderNumber");
  }

  @Test
  void validate_missingOptionalFields_hasNoViolations() {
    InvoiceExtraction extraction = sample();
    for (String optional :
        List.of(
            "orderDate",
            "sellerGstin",
            "sellerPan",
            "sellerAddress",
            "placeOfSupply",
            "placeOfDelivery",
            "reverseCharge",
            "amountInWords",
            "originalInvoiceRef")) {
      extraction = withComponent(extraction, optional, null);
    }

    assertThat(validator.validate(extraction)).isEmpty();
  }

  @Test
  void validate_fieldWithoutValue_isRejected() {
    InvoiceExtraction extraction =
        withComponent(sample(), "sellerName", new Field<String>(null, 0.5, FieldSource.LLM, null));

    assertThat(paths(validator.validate(extraction))).containsExactly("sellerName.value");
  }

  @Test
  void validate_confidenceOutsideZeroToOne_isRejected() {
    InvoiceExtraction tooHigh = withComponent(sample(), "grandTotal", Field.llm(money("1"), 1.01));
    InvoiceExtraction negative = withComponent(sample(), "totalTax", Field.llm(money("0"), -0.01));

    assertThat(paths(validator.validate(tooHigh))).containsExactly("grandTotal.confidence");
    assertThat(paths(validator.validate(negative))).containsExactly("totalTax.confidence");
  }

  @Test
  void validate_confidenceBounds_areAccepted() {
    InvoiceExtraction zero = withComponent(sample(), "grandTotal", Field.llm(money("1"), 0.0));
    InvoiceExtraction one = withComponent(sample(), "grandTotal", Field.llm(money("1"), 1.0));

    assertThat(validator.validate(zero)).isEmpty();
    assertThat(validator.validate(one)).isEmpty();
  }

  @Test
  void validate_noLineItems_isRejected() {
    assertThat(paths(validator.validate(withComponent(sample(), "lineItems", List.of()))))
        .containsExactly("lineItems");
    assertThat(paths(validator.validate(withComponent(sample(), "lineItems", null))))
        .containsExactly("lineItems");
  }

  @Test
  void validate_nestedLineItemViolations_areReported() {
    LineItem line = sample().lineItems().getFirst();
    LineItem noTaxLines =
        new LineItem(
            line.lineNo(),
            line.chargeType(),
            line.description(),
            line.hsnSac(),
            line.unitPrice(),
            line.quantity(),
            line.discount(),
            line.netAmount(),
            List.of(),
            null);

    assertThat(paths(validator.validate(withComponent(sample(), "lineItems", List.of(noTaxLines)))))
        .containsExactlyInAnyOrder("lineItems[0].taxLines", "lineItems[0].lineTotal");
  }

  @Test
  void validate_nestedTaxLineViolation_isReported() {
    LineItem line = sample().lineItems().getFirst();
    TaxLine noRate = new TaxLine(Field.llm(TaxType.IGST, 0.9), null, Field.llm(money("18"), 0.9));
    LineItem withBadTax =
        new LineItem(
            line.lineNo(),
            line.chargeType(),
            line.description(),
            line.hsnSac(),
            line.unitPrice(),
            line.quantity(),
            line.discount(),
            line.netAmount(),
            List.of(noRate),
            line.lineTotal());

    assertThat(paths(validator.validate(withComponent(sample(), "lineItems", List.of(withBadTax)))))
        .containsExactly("lineItems[0].taxLines[0].rate");
  }

  @Test
  void validate_badEvidence_isRejected() {
    Evidence evidence = new Evidence(0, new BoundingBox(-1, 0, 10, 10), List.of(" "));
    InvoiceExtraction extraction =
        withComponent(
            sample(), "sellerName", new Field<>("Example Traders", 0.8, FieldSource.LLM, evidence));

    assertThat(paths(validator.validate(extraction)))
        .containsExactlyInAnyOrder(
            "sellerName.evidence.page",
            "sellerName.evidence.box.x",
            "sellerName.evidence.lineRefs[0].<list element>");
  }

  @Test
  void evidence_nullLineRefs_becomeEmptyAndListsAreCopied() {
    List<String> refs = new ArrayList<>(List.of("P1L1"));
    Evidence evidence = new Evidence(1, null, refs);
    refs.add("P1L2");

    assertThat(new Evidence(1, null, null).lineRefs()).isEmpty();
    assertThat(evidence.lineRefs()).containsExactly("P1L1");
    assertThatThrownBy(() -> evidence.lineRefs().add("P1L3"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void fieldLlm_setsSourceAndNoEvidence() {
    Field<String> field = Field.llm("Example Traders", 0.7);

    assertThat(field.source()).isEqualTo(FieldSource.LLM);
    assertThat(field.evidence()).isNull();
    assertThat(field.confidence()).isEqualTo(0.7);
  }

  /** A concrete type so Jackson knows the field's value type. */
  record TaxLineTypeField(TaxType value, double confidence, FieldSource source) {}

  private static InvoiceExtraction sample() {
    Evidence totalEvidence = new Evidence(1, new BoundingBox(400, 700, 80, 12), List.of("P1L24"));
    TaxLine cgst =
        new TaxLine(
            Field.llm(TaxType.CGST, 0.95), Field.llm(rate("9"), 0.95), Field.llm(money("90"), 0.9));
    TaxLine sgst =
        new TaxLine(
            Field.llm(TaxType.SGST, 0.95), Field.llm(rate("9"), 0.95), Field.llm(money("90"), 0.9));
    LineItem product =
        new LineItem(
            Field.llm(1, 1.0),
            Field.llm(ChargeType.PRODUCT, 0.9),
            Field.llm("Steel water bottle, 1 L", 0.9),
            Field.llm("73239310", 0.85),
            Field.llm(money("1000"), 0.9),
            Field.llm(new BigDecimal("1"), 0.95),
            Field.llm(money("0"), 0.6),
            Field.llm(money("1000"), 0.9),
            List.of(cgst, sgst),
            Field.llm(money("1180"), 0.9));
    return new InvoiceExtraction(
        Field.llm(DocumentClass.TAX_INVOICE, 0.95),
        Field.llm("INVNO_TOKEN_01", 0.9),
        Field.llm(LocalDate.of(2025, 3, 14), 0.9),
        Field.llm("ORDNO_TOKEN_01", 0.9),
        Field.llm(LocalDate.of(2025, 3, 12), 0.8),
        Field.llm("Example Traders", 0.8),
        Field.llm(SELLER_GSTIN, 0.9),
        Field.llm(SELLER_GSTIN.substring(2, 12), 0.7),
        Field.llm("Unit 4, Example Industrial Estate, Mumbai", 0.6),
        Field.llm("Maharashtra", 0.9),
        Field.llm("Maharashtra", 0.9),
        Field.llm("Maharashtra", 0.9),
        Field.llm(false, 0.9),
        new Field<>(Currency.INR, 1.0, FieldSource.RULE, null),
        Field.llm(money("1000"), 0.9),
        Field.llm(money("180"), 0.9),
        new Field<>(money("1180"), 0.9, FieldSource.LLM, totalEvidence),
        Field.llm("One Thousand One Hundred Eighty Only", 0.8),
        null,
        List.of(product));
  }

  /** Copies {@code extraction} with one record component replaced, by name. */
  private static InvoiceExtraction withComponent(
      InvoiceExtraction extraction, String name, Object value) {
    var components = InvoiceExtraction.class.getRecordComponents();
    Object[] args = new Object[components.length];
    Class<?>[] types = new Class<?>[components.length];
    boolean found = false;
    try {
      for (int i = 0; i < components.length; i++) {
        types[i] = components[i].getType();
        boolean match = components[i].getName().equals(name);
        found |= match;
        args[i] = match ? value : components[i].getAccessor().invoke(extraction);
      }
      if (!found) {
        throw new IllegalArgumentException("no component " + name);
      }
      return InvoiceExtraction.class.getDeclaredConstructor(types).newInstance(args);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Set<String> paths(Set<? extends ConstraintViolation<?>> violations) {
    return violations.stream().map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet());
  }

  private static BigDecimal money(String amount) {
    return new BigDecimal(amount).setScale(2, RoundingMode.HALF_UP);
  }

  private static BigDecimal rate(String percent) {
    return new BigDecimal(percent);
  }
}
