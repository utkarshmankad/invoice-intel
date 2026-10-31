package dev.invoiceintel.validate;

import dev.invoiceintel.common.DocumentClass;
import dev.invoiceintel.common.extraction.Field;
import dev.invoiceintel.common.extraction.InvoiceExtraction;
import dev.invoiceintel.common.extraction.LineItem;
import dev.invoiceintel.common.extraction.TaxLine;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Turns an extraction and its rule results into the VAL-8 decision (architecture.md §6.6):
 *
 * <pre>
 * score = min(confidence of required fields)
 *       × (any FAIL ? 0 : 1) × 0.9^WARNs × (class hint and LLM class agree ? 1 : 0.8)
 * </pre>
 *
 * AUTO_APPROVED when the score, rounded to 4 decimals, is at least the threshold and no rule
 * failed; NEEDS_REVIEW otherwise. A missing extraction is FAILED ({@link #missingExtraction}).
 * Required fields are the schema's required ones (specs.md §5.2), on every line and tax line too; a
 * missing one counts as confidence 0. A class hint of {@link DocumentClass#OTHER} means the Text
 * stage had none, so it never disagrees.
 */
public final class ConfidenceScorer {

  /** Starting threshold, tuned later from eval runs (spec §9: ≤ 2% false auto-approvals). */
  public static final BigDecimal DEFAULT_APPROVE_THRESHOLD = new BigDecimal("0.85");

  static final BigDecimal WARN_FACTOR = new BigDecimal("0.9");
  static final BigDecimal CLASS_MISMATCH_FACTOR = new BigDecimal("0.8");
  private static final int SCALE = 4;

  private final BigDecimal approveThreshold;

  public ConfidenceScorer(BigDecimal approveThreshold) {
    Objects.requireNonNull(approveThreshold, "approveThreshold");
    if (approveThreshold.signum() <= 0 || approveThreshold.compareTo(BigDecimal.ONE) > 0) {
      throw new IllegalArgumentException("approveThreshold must be within (0, 1]");
    }
    this.approveThreshold = approveThreshold;
  }

  public ConfidenceScorer() {
    this(DEFAULT_APPROVE_THRESHOLD);
  }

  /** The decision for a document whose extraction is missing. */
  public static Decision missingExtraction() {
    return new Decision(
        BigDecimal.ZERO.setScale(SCALE),
        DecisionOutcome.FAILED,
        List.of(
            DecisionReason.of(
                DecisionReason.Code.EXTRACTION_MISSING, "No extraction to validate")));
  }

  public Decision decide(
      InvoiceExtraction extraction, List<ValidationResult> results, ValidationContext context) {
    Objects.requireNonNull(extraction, "extraction");
    Objects.requireNonNull(results, "results");
    Objects.requireNonNull(context, "context");

    var reasons = new ArrayList<DecisionReason>();
    boolean failed = false;
    int warns = 0;
    for (ValidationResult result : results) {
      switch (result.outcome()) {
        case FAIL -> {
          failed = true;
          reasons.add(
              new DecisionReason(DecisionReason.Code.RULE_FAIL, result.ruleId(), result.message()));
        }
        case WARN -> {
          warns++;
          reasons.add(
              new DecisionReason(DecisionReason.Code.RULE_WARN, result.ruleId(), result.message()));
        }
        case PASS -> {
          // no effect on the score
        }
      }
    }

    LowestField lowest = lowestRequiredField(extraction);
    BigDecimal score =
        failed ? BigDecimal.ZERO : lowest.confidence().multiply(WARN_FACTOR.pow(warns));

    DocumentClass hint = context.classifierClass();
    Field<DocumentClass> llmClass = extraction.documentClass();
    if (hint != DocumentClass.OTHER && (llmClass == null || llmClass.value() != hint)) {
      score = score.multiply(CLASS_MISMATCH_FACTOR);
      reasons.add(
          DecisionReason.of(
              DecisionReason.Code.CLASS_MISMATCH,
              "Class hint "
                  + hint
                  + " differs from extracted class "
                  + (llmClass == null ? "(none)" : llmClass.value())));
    }
    score = score.setScale(SCALE, RoundingMode.HALF_UP);

    if (failed) {
      return new Decision(score, DecisionOutcome.NEEDS_REVIEW, reasons);
    }
    if (score.compareTo(approveThreshold) >= 0) {
      return new Decision(score, DecisionOutcome.AUTO_APPROVED, reasons);
    }
    reasons.add(
        DecisionReason.of(
            DecisionReason.Code.LOW_CONFIDENCE,
            "Score "
                + score.toPlainString()
                + " below "
                + approveThreshold.toPlainString()
                + "; lowest required field "
                + lowest.path()
                + " at "
                + lowest.confidence().setScale(2, RoundingMode.HALF_UP).toPlainString()));
    return new Decision(score, DecisionOutcome.NEEDS_REVIEW, reasons);
  }

  private record LowestField(String path, BigDecimal confidence) {}

  private static LowestField lowestRequiredField(InvoiceExtraction x) {
    var min = new LowestField[1];
    FieldVisitor visit =
        (path, field) -> {
          BigDecimal confidence =
              field == null ? BigDecimal.ZERO : BigDecimal.valueOf(field.confidence());
          if (min[0] == null || confidence.compareTo(min[0].confidence()) < 0) {
            min[0] = new LowestField(path, confidence);
          }
        };
    visit.accept("documentClass", x.documentClass());
    visit.accept("invoiceNumber", x.invoiceNumber());
    visit.accept("invoiceDate", x.invoiceDate());
    visit.accept("orderNumber", x.orderNumber());
    visit.accept("sellerName", x.sellerName());
    visit.accept("buyerState", x.buyerState());
    visit.accept("currency", x.currency());
    visit.accept("subtotal", x.subtotal());
    visit.accept("totalTax", x.totalTax());
    visit.accept("grandTotal", x.grandTotal());
    List<LineItem> lines = x.lineItems() == null ? List.of() : x.lineItems();
    for (int i = 0; i < lines.size(); i++) {
      LineItem line = lines.get(i);
      String p = "lineItems[" + i + "].";
      visit.accept(p + "lineNo", line.lineNo());
      visit.accept(p + "chargeType", line.chargeType());
      visit.accept(p + "description", line.description());
      visit.accept(p + "unitPrice", line.unitPrice());
      visit.accept(p + "quantity", line.quantity());
      visit.accept(p + "netAmount", line.netAmount());
      visit.accept(p + "lineTotal", line.lineTotal());
      List<TaxLine> taxes = line.taxLines() == null ? List.of() : line.taxLines();
      for (int j = 0; j < taxes.size(); j++) {
        TaxLine tax = taxes.get(j);
        String t = p + "taxLines[" + j + "].";
        visit.accept(t + "type", tax.type());
        visit.accept(t + "rate", tax.rate());
        visit.accept(t + "amount", tax.amount());
      }
    }
    return min[0];
  }

  @FunctionalInterface
  private interface FieldVisitor {
    void accept(String path, Field<?> field);
  }
}
