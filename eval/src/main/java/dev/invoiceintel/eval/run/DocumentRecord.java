package dev.invoiceintel.eval.run;

import dev.invoiceintel.eval.metrics.fields.HeaderField;
import dev.invoiceintel.eval.metrics.fields.LineItemCounts;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * One row of a run's {@code documents.csv}: identifiers, outcome, field names, counts, cost and
 * timing only, never a value (.claude/rules/pii.md).
 *
 * @param sha256 the document's SHA-256
 * @param synthetic whether the label is synthetic
 * @param status the runner's outcome
 * @param decision the VAL-8 decision, {@code null} for a skipped document
 * @param mismatchedFields header fields that didn't match the label, {@code null} unless {@code
 *     status} is {@link DocumentResult.Status#EXTRACTED} (only extractions are scored)
 * @param lineItems line-item counts, {@code null} unless extracted
 * @param costUsd the document's model cost
 * @param latencyMs wall time of the runner call
 * @param error the exception's class name, or the pipeline's stage and failure code, for a failure,
 *     else empty
 */
public record DocumentRecord(
    String sha256,
    boolean synthetic,
    DocumentResult.Status status,
    Decision decision,
    Set<HeaderField> mismatchedFields,
    LineItemCounts lineItems,
    BigDecimal costUsd,
    long latencyMs,
    String error) {

  public DocumentRecord {
    Objects.requireNonNull(sha256, "sha256");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(costUsd, "costUsd");
    Objects.requireNonNull(error, "error");
    if ((mismatchedFields == null) != (lineItems == null)) {
      throw new IllegalArgumentException("mismatchedFields and lineItems are scored together");
    }
    mismatchedFields =
        mismatchedFields == null
            ? null
            : mismatchedFields.isEmpty()
                ? Collections.unmodifiableSet(EnumSet.noneOf(HeaderField.class))
                : Collections.unmodifiableSet(EnumSet.copyOf(mismatchedFields));
  }

  /** Whether the extraction was scored against the label. */
  public boolean scored() {
    return mismatchedFields != null;
  }

  /**
   * Scored, every header field matched the label and every line item was matched one-to-one: the
   * "correct" of spec §9's straight-through and false auto-approval rates.
   */
  public boolean correct() {
    return scored()
        && mismatchedFields.isEmpty()
        && lineItems.falsePositives() == 0
        && lineItems.falseNegatives() == 0;
  }
}
