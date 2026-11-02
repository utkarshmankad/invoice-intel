package dev.invoiceintel.pipeline.internal;

import dev.invoiceintel.common.DocumentClass;
import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.pipeline.DocumentState;
import dev.invoiceintel.pipeline.Stage;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The {@code document} columns the document API shows (architecture.md §7, §8); no content. */
public final class DocumentDetailsRepository {

  private final JdbcClient jdbc;

  public DocumentDetailsRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  public Optional<DocumentRow> find(DocumentId id) {
    return jdbc.sql(
            "SELECT status, document_class, failed_stage, failure_code FROM document WHERE id = ?")
        .param(id.value())
        .query(
            (rs, row) -> {
              String documentClass = rs.getString("document_class");
              String failedStage = rs.getString("failed_stage");
              return new DocumentRow(
                  DocumentState.valueOf(rs.getString("status")),
                  documentClass == null ? null : DocumentClass.valueOf(documentClass),
                  failedStage == null ? null : Stage.valueOf(failedStage),
                  rs.getString("failure_code"));
            })
        .optional();
  }

  /**
   * A document's status columns.
   *
   * @param documentClass null until the Text stage set it
   * @param failedStage set exactly when FAILED
   * @param failureCode set exactly when FAILED
   */
  public record DocumentRow(
      DocumentState state, DocumentClass documentClass, Stage failedStage, String failureCode) {}
}
