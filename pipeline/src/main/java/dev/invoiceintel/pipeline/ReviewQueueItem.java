package dev.invoiceintel.pipeline;

import dev.invoiceintel.common.DocumentClass;
import dev.invoiceintel.common.DocumentId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One document waiting for review (REV-1, architecture.md §6.8): when it arrived, its class and its
 * VAL-8 score and reasons. Holds no document text, extraction values or page images; reason
 * messages name rules, fields and amounts only (PII-8).
 *
 * @param receivedAt when the document was uploaded
 * @param documentClass the Text stage's class hint (TXT-4), or {@code null} if it set none
 * @param score the decision's score, or {@code null} if the document has no decision row
 * @param reasons the decision's reasons in stored order; empty without a decision
 */
public record ReviewQueueItem(
    DocumentId id,
    Instant receivedAt,
    DocumentClass documentClass,
    BigDecimal score,
    List<DocumentDetails.Reason> reasons) {

  public ReviewQueueItem {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(receivedAt, "receivedAt");
    reasons = List.copyOf(reasons);
  }
}
