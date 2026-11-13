package dev.invoiceintel.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import dev.invoiceintel.app.ui.ReviewQueueView.Direction;
import dev.invoiceintel.app.ui.ReviewQueueView.QueueRow;
import dev.invoiceintel.common.DocumentClass;
import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.pipeline.DocumentDetails;
import dev.invoiceintel.pipeline.ReviewQueueItem;
import dev.invoiceintel.pipeline.ReviewQueueReader.Sort;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** REV-1: the review queue's sort links and row formatting (architecture.md §6.8). */
class ReviewQueueViewTest {

  @ParameterizedTest
  @CsvSource({
    // column, active, dir -> link
    "RECEIVED, RECEIVED, ASC,  /review?sort=RECEIVED&dir=DESC",
    "RECEIVED, RECEIVED, DESC, /review?sort=RECEIVED&dir=ASC",
    "SCORE,    RECEIVED, ASC,  /review?sort=SCORE&dir=ASC",
    "SCORE,    RECEIVED, DESC, /review?sort=SCORE&dir=ASC",
    "SCORE,    SCORE,    ASC,  /review?sort=SCORE&dir=DESC",
  })
  void link_activeColumnFlipsDirection_otherColumnStartsAscending(
      Sort column, Sort active, Direction dir, String expected) {
    assertThat(ReviewQueueView.link(column, active, dir)).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource({
    "RECEIVED, RECEIVED, ASC,  ascending",
    "RECEIVED, RECEIVED, DESC, descending",
    "SCORE,    RECEIVED, ASC,  none",
    "SCORE,    SCORE,    DESC, descending",
  })
  void aria_marksOnlyTheActiveColumn(Sort column, Sort active, Direction dir, String expected) {
    assertThat(ReviewQueueView.aria(column, active, dir)).isEqualTo(expected);
  }

  @Test
  void queueRowOf_withDecision_formatsUtcTimeAndScore() {
    var id = new DocumentId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    var reason = new DocumentDetails.Reason("RULE_WARN", "VAL-4", "seller GSTIN missing");
    var item =
        new ReviewQueueItem(
            id,
            Instant.parse("2026-03-04T05:06:59Z"),
            DocumentClass.TAX_INVOICE,
            new BigDecimal("0.7290"),
            List.of(reason));

    var row = QueueRow.of(item);

    assertThat(row)
        .isEqualTo(
            new QueueRow(
                "00000000-0000-0000-0000-000000000001",
                "2026-03-04 05:06 UTC",
                "TAX_INVOICE",
                "0.7290",
                List.of(reason)));
  }

  @Test
  void queueRowOf_withoutClassOrDecision_leavesThemBlank() {
    var item =
        new ReviewQueueItem(
            DocumentId.random(), Instant.parse("2026-03-04T05:06:00Z"), null, null, List.of());

    var row = QueueRow.of(item);

    assertThat(row.documentClass()).isEmpty();
    assertThat(row.score()).isEmpty();
    assertThat(row.reasons()).isEmpty();
  }
}
