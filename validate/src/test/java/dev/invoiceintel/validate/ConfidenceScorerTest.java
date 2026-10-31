package dev.invoiceintel.validate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.invoiceintel.common.DocumentClass;
import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.common.extraction.Field;
import dev.invoiceintel.common.extraction.InvoiceExtraction;
import dev.invoiceintel.common.extraction.LineItem;
import dev.invoiceintel.common.extraction.TaxLine;
import dev.invoiceintel.common.extraction.TaxType;
import dev.invoiceintel.validate.DecisionReason.Code;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ConfidenceScorerTest {

  private static final ValidationContext TAX_INVOICE_HINT =
      new ValidationContext(DocumentId.random(), DocumentClass.TAX_INVOICE);
  private static final ValidationContext NO_HINT =
      new ValidationContext(DocumentId.random(), DocumentClass.OTHER);

  private static final ValidationResult PASS =
      new ValidationResult("VAL-1", RuleOutcome.PASS, null);

  private final ConfidenceScorer scorer = new ConfidenceScorer();

  /** Every required field at {@code confidence}. */
  private static InvoiceExtraction uniform(double confidence) {
    InvoiceExtraction x = ExtractionFixtures.consistentTaxInvoice();
    LineItem l = x.lineItems().getFirst();
    TaxLine t = l.taxLines().getFirst();
    TaxLine tax =
        new TaxLine(f(t.type(), confidence), f(t.rate(), confidence), f(t.amount(), confidence));
    LineItem line =
        new LineItem(
            f(l.lineNo(), confidence),
            f(l.chargeType(), confidence),
            f(l.description(), confidence),
            l.hsnSac(),
            f(l.unitPrice(), confidence),
            f(l.quantity(), confidence),
            l.discount(),
            f(l.netAmount(), confidence),
            List.of(tax),
            f(l.lineTotal(), confidence));
    return new InvoiceExtraction(
        f(x.documentClass(), confidence),
        f(x.invoiceNumber(), confidence),
        f(x.invoiceDate(), confidence),
        f(x.orderNumber(), confidence),
        x.orderDate(),
        f(x.sellerName(), confidence),
        x.sellerGstin(),
        x.sellerPan(),
        x.sellerAddress(),
        x.placeOfSupply(),
        x.placeOfDelivery(),
        f(x.buyerState(), confidence),
        x.reverseCharge(),
        f(x.currency(), confidence),
        f(x.subtotal(), confidence),
        f(x.totalTax(), confidence),
        f(x.grandTotal(), confidence),
        x.amountInWords(),
        x.originalInvoiceRef(),
        List.of(line));
  }

  private static <T> Field<T> f(Field<T> field, double confidence) {
    return Field.llm(field.value(), confidence);
  }

  private static ValidationResult warn(String ruleId) {
    return new ValidationResult(ruleId, RuleOutcome.WARN, ruleId + " warned");
  }

  private static ValidationResult fail(String ruleId) {
    return new ValidationResult(ruleId, RuleOutcome.FAIL, ruleId + " failed");
  }

  @Test
  void decide_confidentAndAllPass_isAutoApprovedWithoutReasons() {
    Decision decision = scorer.decide(uniform(0.95), List.of(PASS), TAX_INVOICE_HINT);

    assertThat(decision.outcome()).isEqualTo(DecisionOutcome.AUTO_APPROVED);
    assertThat(decision.score()).isEqualByComparingTo("0.95");
    assertThat(decision.score().scale()).isEqualTo(4);
    assertThat(decision.reasons()).isEmpty();
  }

  @Test
  void decide_scoreIsLowestRequiredFieldConfidence() {
    // The fixture's lowest required field is sellerName at 0.8.
    Decision decision =
        scorer.decide(ExtractionFixtures.consistentTaxInvoice(), List.of(), TAX_INVOICE_HINT);

    assertThat(decision.score()).isEqualByComparingTo("0.80");
    assertThat(decision.outcome()).isEqualTo(DecisionOutcome.NEEDS_REVIEW);
    assertThat(decision.reasons())
        .containsExactly(
            DecisionReason.of(
                Code.LOW_CONFIDENCE,
                "Score 0.8000 below 0.85; lowest required field sellerName at 0.80"));
  }

  @Test
  void decide_lowConfidenceOnTaxLine_namesItsPath() {
    InvoiceExtraction x = uniform(0.99);
    LineItem l = x.lineItems().getFirst();
    TaxLine t = l.taxLines().getFirst();
    LineItem line =
        new LineItem(
            l.lineNo(),
            l.chargeType(),
            l.description(),
            l.hsnSac(),
            l.unitPrice(),
            l.quantity(),
            l.discount(),
            l.netAmount(),
            List.of(
                t,
                new TaxLine(
                    Field.llm(TaxType.SGST, 0.99), t.rate(), Field.llm(t.amount().value(), 0.4))),
            l.lineTotal());
    x = withLines(x, List.of(l, line));

    Decision decision = scorer.decide(x, List.of(), NO_HINT);

    assertThat(decision.score()).isEqualByComparingTo("0.40");
    assertThat(decision.reasons().getLast().message())
        .endsWith("lowest required field lineItems[1].taxLines[1].amount at 0.40");
  }

  @Test
  void decide_optionalFieldsDontCount() {
    InvoiceExtraction x = uniform(1.0);
    x =
        new InvoiceExtraction(
            x.documentClass(),
            x.invoiceNumber(),
            x.invoiceDate(),
            x.orderNumber(),
            Field.llm(x.invoiceDate().value(), 0.1),
            x.sellerName(),
            Field.llm("27ABCDE1234F1Z5", 0.1),
            x.sellerPan(),
            x.sellerAddress(),
            Field.llm("Maharashtra", 0.1),
            x.placeOfDelivery(),
            x.buyerState(),
            x.reverseCharge(),
            x.currency(),
            x.subtotal(),
            x.totalTax(),
            x.grandTotal(),
            Field.llm("Rupees One Thousand Only", 0.1),
            x.originalInvoiceRef(),
            x.lineItems());

    assertThat(scorer.decide(x, List.of(), NO_HINT).score()).isEqualByComparingTo("1");
  }

  @Test
  void decide_missingRequiredField_countsAsZero() {
    InvoiceExtraction x = uniform(1.0);
    x =
        new InvoiceExtraction(
            x.documentClass(),
            x.invoiceNumber(),
            x.invoiceDate(),
            x.orderNumber(),
            x.orderDate(),
            null,
            x.sellerGstin(),
            x.sellerPan(),
            x.sellerAddress(),
            x.placeOfSupply(),
            x.placeOfDelivery(),
            x.buyerState(),
            x.reverseCharge(),
            x.currency(),
            x.subtotal(),
            x.totalTax(),
            x.grandTotal(),
            x.amountInWords(),
            x.originalInvoiceRef(),
            x.lineItems());

    Decision decision = scorer.decide(x, List.of(), NO_HINT);

    assertThat(decision.score()).isEqualByComparingTo("0");
    assertThat(decision.reasons().getLast().message()).endsWith("sellerName at 0.00");
  }

  @Test
  void decide_anyFail_scoresZeroAndNeedsReview_listingRuleReasonsInOrder() {
    Decision decision =
        scorer.decide(uniform(1.0), List.of(warn("VAL-2"), PASS, fail("VAL-3")), TAX_INVOICE_HINT);

    assertThat(decision.score()).isEqualByComparingTo("0");
    assertThat(decision.outcome()).isEqualTo(DecisionOutcome.NEEDS_REVIEW);
    assertThat(decision.reasons())
        .containsExactly(
            new DecisionReason(Code.RULE_WARN, "VAL-2", "VAL-2 warned"),
            new DecisionReason(Code.RULE_FAIL, "VAL-3", "VAL-3 failed"));
  }

  @ParameterizedTest(name = "{0} WARNs at confidence 1 → {1}, {2}")
  @CsvSource({
    "0, 1.0000, AUTO_APPROVED",
    "1, 0.9000, AUTO_APPROVED",
    "2, 0.8100, NEEDS_REVIEW",
    "3, 0.7290, NEEDS_REVIEW"
  })
  void decide_eachWarn_multipliesByPointNine(int warns, String score, DecisionOutcome outcome) {
    List<ValidationResult> results =
        java.util.stream.IntStream.range(0, warns).mapToObj(i -> warn("VAL-" + (i + 1))).toList();

    Decision decision = scorer.decide(uniform(1.0), results, NO_HINT);

    assertThat(decision.score()).isEqualTo(new BigDecimal(score));
    assertThat(decision.outcome()).isEqualTo(outcome);
    assertThat(decision.reasons()).filteredOn(r -> r.code() == Code.RULE_WARN).hasSize(warns);
  }

  @Test
  void decide_classMismatch_multipliesByPointEight() {
    var creditNoteHint = new ValidationContext(DocumentId.random(), DocumentClass.CREDIT_NOTE);

    Decision decision = scorer.decide(uniform(1.0), List.of(), creditNoteHint);

    assertThat(decision.score()).isEqualByComparingTo("0.80");
    assertThat(decision.outcome()).isEqualTo(DecisionOutcome.NEEDS_REVIEW);
    assertThat(decision.reasons())
        .containsExactly(
            DecisionReason.of(
                Code.CLASS_MISMATCH,
                "Class hint CREDIT_NOTE differs from extracted class TAX_INVOICE"),
            DecisionReason.of(
                Code.LOW_CONFIDENCE,
                "Score 0.8000 below 0.85; lowest required field documentClass at 1.00"));
  }

  @Test
  void decide_noClassHint_neverMismatches() {
    assertThat(scorer.decide(uniform(1.0), List.of(), NO_HINT).reasons()).isEmpty();
  }

  @Test
  void decide_classMismatchWithFail_keepsBothReasons() {
    var hint = new ValidationContext(DocumentId.random(), DocumentClass.BILL_OF_SUPPLY);

    Decision decision = scorer.decide(uniform(1.0), List.of(fail("VAL-5")), hint);

    assertThat(decision.reasons())
        .extracting(DecisionReason::code)
        .containsExactly(Code.RULE_FAIL, Code.CLASS_MISMATCH);
    assertThat(decision.score()).isEqualByComparingTo("0");
  }

  @ParameterizedTest(name = "confidence {0} vs threshold 0.85 → {1}")
  @CsvSource({
    "0.85, AUTO_APPROVED",
    "0.84996, AUTO_APPROVED", // rounds to 0.8500
    "0.84994, NEEDS_REVIEW",
    "0.5, NEEDS_REVIEW"
  })
  void decide_thresholdIsInclusive_onTheRoundedScore(double confidence, DecisionOutcome outcome) {
    assertThat(scorer.decide(uniform(confidence), List.of(), NO_HINT).outcome()).isEqualTo(outcome);
  }

  @Test
  void decide_customThreshold_isUsed() {
    var strict = new ConfidenceScorer(new BigDecimal("0.97"));

    Decision decision = strict.decide(uniform(0.96), List.of(), NO_HINT);

    assertThat(decision.outcome()).isEqualTo(DecisionOutcome.NEEDS_REVIEW);
    assertThat(decision.reasons().getFirst().message()).startsWith("Score 0.9600 below 0.97;");
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-0.1", "1.01"})
  void constructor_thresholdOutsideRange_isRejected(String threshold) {
    assertThatThrownBy(() -> new ConfidenceScorer(new BigDecimal(threshold)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void constructor_thresholdOne_isAllowed() {
    assertThat(
            new ConfidenceScorer(BigDecimal.ONE).decide(uniform(1.0), List.of(), NO_HINT).outcome())
        .isEqualTo(DecisionOutcome.AUTO_APPROVED);
  }

  @Test
  void missingExtraction_isFailedWithReason() {
    Decision decision = ConfidenceScorer.missingExtraction();

    assertThat(decision.outcome()).isEqualTo(DecisionOutcome.FAILED);
    assertThat(decision.score()).isEqualByComparingTo("0");
    assertThat(decision.reasons())
        .extracting(DecisionReason::code)
        .containsExactly(Code.EXTRACTION_MISSING);
  }

  private static InvoiceExtraction withLines(InvoiceExtraction x, List<LineItem> lines) {
    return new InvoiceExtraction(
        x.documentClass(),
        x.invoiceNumber(),
        x.invoiceDate(),
        x.orderNumber(),
        x.orderDate(),
        x.sellerName(),
        x.sellerGstin(),
        x.sellerPan(),
        x.sellerAddress(),
        x.placeOfSupply(),
        x.placeOfDelivery(),
        x.buyerState(),
        x.reverseCharge(),
        x.currency(),
        x.subtotal(),
        x.totalTax(),
        x.grandTotal(),
        x.amountInWords(),
        x.originalInvoiceRef(),
        lines);
  }
}
