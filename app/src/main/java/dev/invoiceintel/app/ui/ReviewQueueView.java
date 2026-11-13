package dev.invoiceintel.app.ui;

import dev.invoiceintel.pipeline.DocumentDetails;
import dev.invoiceintel.pipeline.ReviewQueueItem;
import dev.invoiceintel.pipeline.ReviewQueueReader.Sort;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Display helpers of the review queue page (REV-1, architecture.md §6.8). */
final class ReviewQueueView {

  private static final DateTimeFormatter RECEIVED =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC);

  private ReviewQueueView() {}

  /**
   * The header link of {@code column}: clicking the active column flips the direction, any other
   * column starts ascending.
   */
  static String link(Sort column, Sort active, Direction dir) {
    Direction next = column == active && dir == Direction.ASC ? Direction.DESC : Direction.ASC;
    return "/review?sort=" + column.name() + "&dir=" + next.name();
  }

  /** The {@code aria-sort} value of {@code column}'s header. */
  static String aria(Sort column, Sort active, Direction dir) {
    if (column != active) {
      return "none";
    }
    return dir == Direction.ASC ? "ascending" : "descending";
  }

  /** Sort direction of the queue. */
  enum Direction {
    ASC,
    DESC
  }

  /**
   * One table row, formatted for display.
   *
   * @param documentClass the class hint, or an empty string without one
   * @param score the score at 4 decimals, or an empty string without a decision
   */
  record QueueRow(
      String id,
      String received,
      String documentClass,
      String score,
      List<DocumentDetails.Reason> reasons) {

    QueueRow {
      reasons = List.copyOf(reasons);
    }

    static QueueRow of(ReviewQueueItem item) {
      return new QueueRow(
          item.id().value().toString(),
          RECEIVED.format(item.receivedAt()),
          item.documentClass() == null ? "" : item.documentClass().name(),
          item.score() == null ? "" : item.score().toPlainString(),
          item.reasons());
    }
  }
}
