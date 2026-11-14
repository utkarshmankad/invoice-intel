package dev.invoiceintel.eval.run;

import dev.invoiceintel.eval.label.LabelValidator;
import tools.jackson.databind.JsonNode;

/**
 * One valid label file of a dataset (architecture.md §9.2). The label holds real values for real
 * documents, so nothing here may be printed or logged (.claude/rules/pii.md); use {@link #sha256()}
 * to name the document.
 *
 * @param sha256 the SHA-256 of the original's bytes, the label's file name without {@code .json}
 * @param synthetic whether the document is generated or degraded (specs §4.3)
 * @param label the label file's JSON, already checked by {@code LabelValidator}
 */
public record LabelledDocument(String sha256, boolean synthetic, JsonNode label) {

  public LabelledDocument {
    // A defensive copy keeps the record immutable for runners and metrics.
    label = label.deepCopy();
  }

  /**
   * Whether the label was exported from a reviewed document (REV-4, ADR-31): its invoice number,
   * order number and original invoice reference are tokens already, and it has no PII spans.
   */
  public boolean pseudonymized() {
    return LabelValidator.isPseudonymized(label);
  }

  /** A copy of the label, so callers can't change this document's ground truth. */
  @Override
  public JsonNode label() {
    return label.deepCopy();
  }
}
