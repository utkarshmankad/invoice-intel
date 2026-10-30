package dev.invoiceintel.pii.internal;

import dev.invoiceintel.common.DocumentId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/** {@code pii_span} rows (architecture.md §7). They hold tokens and boxes, never values. */
public final class PiiSpanRepository {

  /** The union box of a span's words, in the page's unit; stored as {@code bbox} JSONB. */
  public record Box(double x, double y, double w, double h) {}

  /** One row; {@code token} is the span's replacement text. */
  public record Row(
      int pageNo, int start, int end, Box bbox, String type, String detector, String token) {}

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final JdbcClient jdbc;

  public PiiSpanRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  public void insert(DocumentId documentId, int runNo, Row row) {
    jdbc.sql(
            """
            INSERT INTO pii_span
              (id, document_id, run_no, page_no, span_start, span_end, bbox, type, detector, token)
            VALUES (?, ?, ?, ?, ?, ?, CAST(? AS JSONB), ?, ?, ?)
            """)
        .params(
            UUID.randomUUID(),
            documentId.value(),
            runNo,
            row.pageNo(),
            row.start(),
            row.end(),
            JSON.writeValueAsString(row.bbox()),
            row.type(),
            row.detector(),
            row.token())
        .update();
  }

  /** The rows of PII run {@code runNo} of a document, by page and start offset. */
  public List<Row> findByRun(DocumentId documentId, int runNo) {
    return jdbc.sql(
            """
            SELECT page_no, span_start, span_end, bbox, type, detector, token FROM pii_span
            WHERE document_id = ? AND run_no = ? ORDER BY page_no, span_start
            """)
        .params(documentId.value(), runNo)
        .query(
            (rs, row) ->
                new Row(
                    rs.getInt("page_no"),
                    rs.getInt("span_start"),
                    rs.getInt("span_end"),
                    JSON.readValue(rs.getString("bbox"), Box.class),
                    rs.getString("type"),
                    rs.getString("detector"),
                    rs.getString("token")))
        .list();
  }
}
