package dev.invoiceintel.pipeline.internal;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.pipeline.DocumentState;
import dev.invoiceintel.pipeline.Stage;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The {@code stage_run} rows and the {@code document} status columns (architecture.md §5.1, §7).
 * Every write method expects the caller's transaction to hold the document row lock from {@link
 * #lockDocument}, so status changes and stage runs of one document never interleave.
 */
public final class StageRunRepository {

  private final JdbcClient jdbc;

  public StageRunRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  /**
   * Locks the document row for this transaction and returns its state. {@code NO KEY UPDATE} so
   * stage outputs that reference the document (foreign keys) don't block on it.
   */
  public Optional<LockedDocument> lockDocument(DocumentId id) {
    return jdbc.sql("SELECT status, failed_stage FROM document WHERE id = ? FOR NO KEY UPDATE")
        .param(id.value())
        .query(
            (rs, row) -> {
              String failedStage = rs.getString("failed_stage");
              return new LockedDocument(
                  DocumentState.valueOf(rs.getString("status")),
                  failedStage == null ? null : Stage.valueOf(failedStage));
            })
        .optional();
  }

  /**
   * Starts the next run of {@code stage} unless one is already running.
   *
   * @return the new run number, or empty if a run of this stage is in progress
   */
  public OptionalInt startRun(DocumentId id, Stage stage) {
    boolean running =
        jdbc.sql(
                "SELECT EXISTS (SELECT 1 FROM stage_run"
                    + " WHERE document_id = ? AND stage = ? AND status = 'RUNNING')")
            .params(id.value(), stage.name())
            .query(Boolean.class)
            .single();
    if (running) {
      return OptionalInt.empty();
    }
    return OptionalInt.of(
        jdbc.sql(
                """
                INSERT INTO stage_run (id, document_id, stage, run_no, status, started_at)
                SELECT ?, ?, ?, COALESCE(MAX(run_no), 0) + 1, 'RUNNING', now()
                FROM stage_run WHERE document_id = ? AND stage = ?
                RETURNING run_no
                """)
            .params(UUID.randomUUID(), id.value(), stage.name(), id.value(), stage.name())
            .query(Integer.class)
            .single());
  }

  /**
   * Ends a running run as SUCCEEDED or FAILED.
   *
   * @return false if the run is no longer running because a reprocess superseded it
   */
  public boolean endRun(DocumentId id, Stage stage, int runNo, String errorCode) {
    return jdbc.sql(
                """
                UPDATE stage_run
                SET status = ?, error_code = ?, ended_at = now(), updated_at = now()
                WHERE document_id = ? AND stage = ? AND run_no = ? AND status = 'RUNNING'
                """)
            .params(
                errorCode == null ? "SUCCEEDED" : "FAILED",
                errorCode,
                id.value(),
                stage.name(),
                runNo)
            .update()
        == 1;
  }

  /** Supersedes every run of {@code stages} that isn't already superseded. */
  public void supersedeRuns(DocumentId id, List<Stage> stages) {
    for (Stage stage : stages) {
      jdbc.sql(
              """
              UPDATE stage_run
              SET status = 'SUPERSEDED', ended_at = COALESCE(ended_at, now()), updated_at = now()
              WHERE document_id = ? AND stage = ? AND status <> 'SUPERSEDED'
              """)
          .params(id.value(), stage.name())
          .update();
    }
  }

  /** Sets a non-failed state and clears any failure. */
  public void setState(DocumentId id, DocumentState state) {
    jdbc.sql(
            """
            UPDATE document
            SET status = ?, failed_stage = NULL, failure_code = NULL, updated_at = now()
            WHERE id = ?
            """)
        .params(state.name(), id.value())
        .update();
  }

  /** Marks the document FAILED at {@code stage} with {@code code} (architecture.md §5.1). */
  public void setFailed(DocumentId id, Stage stage, String code) {
    jdbc.sql(
            """
            UPDATE document
            SET status = 'FAILED', failed_stage = ?, failure_code = ?, updated_at = now()
            WHERE id = ?
            """)
        .params(stage.name(), code, id.value())
        .update();
  }

  /** The run number of the stage's latest {@code SUCCEEDED} run, if any. */
  public OptionalInt latestSucceededRun(DocumentId id, Stage stage) {
    return jdbc.sql(
            """
            SELECT run_no FROM stage_run
            WHERE document_id = ? AND stage = ? AND status = 'SUCCEEDED'
            ORDER BY run_no DESC LIMIT 1
            """)
        .params(id.value(), stage.name())
        .query(Integer.class)
        .optional()
        .map(OptionalInt::of)
        .orElse(OptionalInt.empty());
  }

  /** A document's state, read under its row lock; {@code failedStage} is set only when FAILED. */
  public record LockedDocument(DocumentState state, Stage failedStage) {

    /**
     * The furthest state the document reached: for a failed document, the input state of the stage
     * that failed.
     */
    public DocumentState reached() {
      return state == DocumentState.FAILED ? failedStage.input() : state;
    }
  }
}
