package dev.invoiceintel.pipeline;

import dev.invoiceintel.common.DocumentClass;
import dev.invoiceintel.common.DocumentId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Reads the review queue (REV-1, architecture.md §6.8): documents in {@code NEEDS_REVIEW} with
 * their decision's score and reasons, at most {@value #LIMIT}. A document leaves {@code
 * NEEDS_REVIEW} as soon as it is reprocessed, so the decision row of a queued document is the one
 * its latest Validate run wrote. Reads {@code document} and {@code decision} only; never document
 * text, extractions, the vault or object storage.
 */
public final class ReviewQueueReader {

  /** Most documents the queue shows. */
  public static final int LIMIT = 100;

  /** What the queue is ordered by; ties fall back to the oldest upload first, then the ID. */
  public enum Sort {
    /** Upload time. */
    RECEIVED("d.created_at"),
    /** Decision score; documents without a decision come last in either direction. */
    SCORE("dc.score");

    private final String column;

    Sort(String column) {
      this.column = column;
    }
  }

  private static final String QUERY =
      """
      WITH queued AS (
        SELECT d.id, d.created_at, d.document_class, dc.score, dc.reasons,
               row_number() OVER (ORDER BY {order}) AS position
        FROM document d
        LEFT JOIN decision dc ON dc.document_id = d.id
        WHERE d.status = 'NEEDS_REVIEW'
        ORDER BY position
        LIMIT ?
      )
      SELECT q.id, q.created_at, q.document_class, q.score,
             r.reason ->> 'code' AS code, r.reason ->> 'ruleId' AS rule_id,
             r.reason ->> 'message' AS message
      FROM queued q
      LEFT JOIN LATERAL jsonb_array_elements(q.reasons) WITH ORDINALITY AS r(reason, n) ON true
      ORDER BY q.position, r.n
      """;

  private final JdbcClient jdbc;

  public ReviewQueueReader(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  /** The queued documents ordered by {@code sort}, ascending unless {@code descending}. */
  public List<ReviewQueueItem> list(Sort sort, boolean descending) {
    Objects.requireNonNull(sort, "sort");
    // Column and direction come from the enum and a boolean, never from request text.
    String order =
        sort.column + (descending ? " DESC" : " ASC") + " NULLS LAST, d.created_at ASC, d.id ASC";
    String sql = QUERY.replace("{order}", order);
    var items = new LinkedHashMap<UUID, Row>();
    jdbc.sql(sql)
        .param(LIMIT)
        .query(
            (ResultSet rs) -> {
              var id = rs.getObject("id", UUID.class);
              var row = items.computeIfAbsent(id, _ -> Row.of(id, rs));
              String code = rs.getString("code");
              if (code != null) {
                row.reasons.add(
                    new DocumentDetails.Reason(
                        code, rs.getString("rule_id"), rs.getString("message")));
              }
            });
    return items.values().stream().map(Row::item).toList();
  }

  /** A queued document while its reason rows are collected. */
  private static final class Row {

    private final ReviewQueueItem head;
    private final List<DocumentDetails.Reason> reasons = new ArrayList<>();

    private Row(ReviewQueueItem head) {
      this.head = head;
    }

    static Row of(UUID id, ResultSet rs) {
      try {
        String documentClass = rs.getString("document_class");
        return new Row(
            new ReviewQueueItem(
                new DocumentId(id),
                rs.getTimestamp("created_at").toInstant(),
                documentClass == null ? null : DocumentClass.valueOf(documentClass),
                rs.getBigDecimal("score"),
                List.of()));
      } catch (SQLException e) {
        throw new IllegalStateException("could not read review queue row", e);
      }
    }

    ReviewQueueItem item() {
      return new ReviewQueueItem(
          head.id(), head.receivedAt(), head.documentClass(), head.score(), reasons);
    }
  }
}
