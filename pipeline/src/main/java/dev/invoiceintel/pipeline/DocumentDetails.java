package dev.invoiceintel.pipeline;

import dev.invoiceintel.common.DocumentClass;
import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.common.extraction.InvoiceExtraction;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * What the document API shows about one document (UC2, architecture.md §8): its state, failure,
 * current extraction, decision and rule results. Only the current outputs of a stage, those of its
 * latest {@code SUCCEEDED} run, appear; superseded runs don't. Holds no document text, original
 * file, page image or vault value: the extraction has pseudonymized values only (specs.md §6), and
 * rule and reason messages name rules, fields and amounts only (PII-8).
 *
 * @param documentClass the Text stage's class hint (TXT-4), or {@code null} before it ran
 * @param failedStage the stage that failed, set exactly when {@code state} is FAILED
 * @param failureCode the failure's code, set exactly when {@code state} is FAILED
 * @param extraction the current extraction, or {@code null} if no Extract run succeeded
 * @param decision the current VAL-8 decision, or {@code null} if no Validate run succeeded
 * @param rules the rule results behind {@code decision}, ordered by rule ID; empty without one
 */
public record DocumentDetails(
    DocumentId id,
    DocumentState state,
    DocumentClass documentClass,
    Stage failedStage,
    String failureCode,
    InvoiceExtraction extraction,
    Decision decision,
    List<RuleCheck> rules) {

  public DocumentDetails {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(state, "state");
    rules = List.copyOf(rules);
  }

  /**
   * A VAL-8 decision (architecture.md §6.6).
   *
   * @param score 0–1 at 4 decimals
   * @param outcome {@code AUTO_APPROVED}, {@code NEEDS_REVIEW} or {@code FAILED}
   */
  public record Decision(BigDecimal score, String outcome, List<Reason> reasons) {

    public Decision {
      Objects.requireNonNull(score, "score");
      Objects.requireNonNull(outcome, "outcome");
      reasons = List.copyOf(reasons);
    }
  }

  /**
   * Why the document got its decision.
   *
   * @param ruleId the rule behind a {@code RULE_FAIL} or {@code RULE_WARN}, else {@code null}
   */
  public record Reason(String code, String ruleId, String message) {}

  /**
   * One rule's result.
   *
   * @param outcome {@code PASS}, {@code WARN} or {@code FAIL}
   * @param message set exactly for {@code WARN} and {@code FAIL}
   */
  public record RuleCheck(String ruleId, String outcome, String message) {}
}
