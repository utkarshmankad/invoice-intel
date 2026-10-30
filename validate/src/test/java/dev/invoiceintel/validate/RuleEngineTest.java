package dev.invoiceintel.validate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.invoiceintel.common.DocumentClass;
import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.common.extraction.InvoiceExtraction;
import java.util.List;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

class RuleEngineTest {

  private static final InvoiceExtraction EXTRACTION = ExtractionFixtures.consistentTaxInvoice();
  private static final ValidationContext CONTEXT =
      new ValidationContext(DocumentId.random(), DocumentClass.TAX_INVOICE);

  private static ValidationRule rule(
      String id, BiFunction<InvoiceExtraction, ValidationContext, RuleResult> check) {
    return new ValidationRule() {
      @Override
      public String id() {
        return id;
      }

      @Override
      public RuleResult check(InvoiceExtraction extraction, ValidationContext context) {
        return check.apply(extraction, context);
      }
    };
  }

  @Test
  void run_returnsOneResultPerRule_inRegistrationOrder() {
    var engine =
        new RuleEngine(
            List.of(
                rule("VAL-3", (x, c) -> RuleResult.fail("grand total off by 5.00")),
                rule("VAL-1", (x, c) -> RuleResult.pass()),
                rule("VAL-2", (x, c) -> RuleResult.warn("rate unusual"))));

    assertThat(engine.run(EXTRACTION, CONTEXT))
        .containsExactly(
            new ValidationResult("VAL-3", RuleOutcome.FAIL, "grand total off by 5.00"),
            new ValidationResult("VAL-1", RuleOutcome.PASS, null),
            new ValidationResult("VAL-2", RuleOutcome.WARN, "rate unusual"));
  }

  @Test
  void run_passesExtractionAndContextToEachRule() {
    var engine =
        new RuleEngine(
            List.of(
                rule(
                    "VAL-9",
                    (x, c) ->
                        x == EXTRACTION && c == CONTEXT
                            ? RuleResult.pass()
                            : RuleResult.fail("wrong input"))));

    assertThat(engine.run(EXTRACTION, CONTEXT))
        .extracting(ValidationResult::outcome)
        .containsExactly(RuleOutcome.PASS);
  }

  @Test
  void run_ruleThrows_isFailWithoutTheExceptionText() {
    var engine =
        new RuleEngine(
            List.of(
                rule(
                    "VAL-7",
                    (x, c) -> {
                      throw new IllegalStateException("cannot parse INVNO_TOKEN_01");
                    }),
                rule("VAL-1", (x, c) -> RuleResult.pass())));

    assertThat(engine.run(EXTRACTION, CONTEXT))
        .containsExactly(
            new ValidationResult("VAL-7", RuleOutcome.FAIL, RuleEngine.RULE_ERROR),
            new ValidationResult("VAL-1", RuleOutcome.PASS, null));
  }

  @Test
  void run_ruleReturnsNull_isFail() {
    var engine = new RuleEngine(List.of(rule("VAL-5", (x, c) -> null)));

    assertThat(engine.run(EXTRACTION, CONTEXT))
        .containsExactly(new ValidationResult("VAL-5", RuleOutcome.FAIL, RuleEngine.RULE_ERROR));
  }

  @Test
  void run_noRules_isEmpty() {
    assertThat(new RuleEngine(List.of()).run(EXTRACTION, CONTEXT)).isEmpty();
  }

  @Test
  void run_nullInput_isRejected() {
    var engine = new RuleEngine(List.of());

    assertThatThrownBy(() -> engine.run(null, CONTEXT)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> engine.run(EXTRACTION, null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void constructor_duplicateRuleId_isRejected() {
    var rules = List.of(rule("VAL-1", (x, c) -> RuleResult.pass()), rule("VAL-1", (x, c) -> null));

    assertThatThrownBy(() -> new RuleEngine(rules))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("VAL-1");
  }

  @Test
  void constructor_nullRuleId_isRejected() {
    var rules = List.of(rule(null, (x, c) -> RuleResult.pass()));

    assertThatThrownBy(() -> new RuleEngine(rules)).isInstanceOf(NullPointerException.class);
  }
}
