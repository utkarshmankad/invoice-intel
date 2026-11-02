package dev.invoiceintel.pipeline;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.common.logging.LogContext;
import dev.invoiceintel.common.metrics.InvoiceMetrics;
import dev.invoiceintel.pii.LeakChecker;
import dev.invoiceintel.pipeline.internal.StageRunRepository;
import dev.invoiceintel.validate.DecisionOutcome;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import org.jobrunr.scheduling.JobRequestScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drives documents through the stage chain of architecture.md §5.1. Each stage runs as a JobRunr
 * job ({@link StageJob}); a run is recorded in {@code stage_run}, and a successful run advances the
 * document in the same transaction as the stage's outputs, then enqueues the next stage if it has a
 * handler. Stage failures mark the document {@code FAILED}; they are not retried by JobRunr, the
 * owner reprocesses instead (NFR-5).
 *
 * <p>After each run it reports the run's duration and end status, and the document's decision when
 * the run settled one, to {@link InvoiceMetrics} (architecture.md §12). Both are reported only once
 * the run's transaction has ended, so rolled-back work is never counted as succeeded.
 */
public final class PipelineService {

  /** Failure code for exceptions that aren't a {@link StageFailedException}. */
  public static final String UNEXPECTED_ERROR = "UNEXPECTED_ERROR";

  private static final Logger log = LoggerFactory.getLogger(PipelineService.class);

  /** Run end statuses, as in {@code stage_run.status}; the {@code outcome} tag of §12. */
  static final String SUCCEEDED = "SUCCEEDED";

  static final String FAILED = "FAILED";
  static final String SUPERSEDED = "SUPERSEDED";

  private final StageRunRepository runs;
  private final TransactionTemplate tx;
  private final JobRequestScheduler jobs;
  private final Map<Stage, StageHandler> handlers;
  private final InvoiceMetrics metrics;

  public PipelineService(
      JdbcClient jdbc,
      PlatformTransactionManager transactionManager,
      JobRequestScheduler jobs,
      List<StageHandler> handlers,
      InvoiceMetrics metrics) {
    this.runs = new StageRunRepository(jdbc);
    this.tx = new TransactionTemplate(Objects.requireNonNull(transactionManager, "tx"));
    this.jobs = Objects.requireNonNull(jobs, "jobs");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    var byStage = new EnumMap<Stage, StageHandler>(Stage.class);
    for (StageHandler handler : handlers) {
      if (byStage.put(handler.stage(), handler) != null) {
        throw new IllegalArgumentException("more than one handler for stage " + handler.stage());
      }
    }
    this.handlers = Map.copyOf(byStage);
  }

  /** Enqueues the first stage for a newly received document. */
  public void start(DocumentId id) {
    submit(id, Stage.TEXT);
  }

  /**
   * Re-runs a document from {@code fromStage} (NFR-5): supersedes the runs of that stage and every
   * later one, moves the document back to the stage's input state and enqueues the stage. Outputs
   * of superseded runs stay in place but are no longer current.
   *
   * @return false if there is no such document
   * @throws ReprocessNotAllowedException if the document hasn't reached {@code fromStage} or the
   *     stage has no handler yet
   */
  public boolean reprocess(DocumentId id, Stage fromStage) {
    Objects.requireNonNull(fromStage, "fromStage");
    if (!handlers.containsKey(fromStage)) {
      throw new ReprocessNotAllowedException("stage " + fromStage + " is not available yet");
    }
    boolean found =
        Boolean.TRUE.equals(
            tx.execute(
                _ -> {
                  var document = runs.lockDocument(id);
                  if (document.isEmpty()) {
                    return false;
                  }
                  DocumentState reached = document.get().reached();
                  if (reached.compareTo(fromStage.input()) < 0) {
                    throw new ReprocessNotAllowedException(
                        "document in " + reached + " has not reached stage " + fromStage);
                  }
                  var stages = Arrays.asList(Stage.values());
                  runs.supersedeRuns(id, stages.subList(fromStage.ordinal(), stages.size()));
                  runs.setState(id, fromStage.input());
                  return true;
                }));
    if (found) {
      try (var _ = LogContext.forStage(id.value(), fromStage.name())) {
        log.info("document reprocessing");
      }
      submit(id, fromStage);
    }
    return found;
  }

  /** Runs one stage for one document; called by {@link StageJobHandler}. */
  void runStage(DocumentId id, Stage stage) {
    StageHandler handler = handlers.get(stage);
    if (handler == null) {
      throw new IllegalStateException("no handler for stage " + stage);
    }
    OptionalInt started = tx.execute(_ -> startRun(id, stage));
    try (var _ = LogContext.forStage(id.value(), stage.name())) {
      if (started == null || started.isEmpty()) {
        log.info("stage skipped: document not in its input state or stage already running");
        return;
      }
    }
    int runNo = started.getAsInt();
    try (var _ = LogContext.forStageRun(id.value(), stage.name(), runNo)) {
      if (succeed(id, stage, handler, runNo)) {
        log.info("stage succeeded");
        stage.next().filter(handlers::containsKey).ifPresent(next -> submit(id, next));
      }
    }
  }

  private OptionalInt startRun(DocumentId id, Stage stage) {
    var document = runs.lockDocument(id);
    if (document.isEmpty() || document.get().state() != stage.input()) {
      return OptionalInt.empty();
    }
    return runs.startRun(id, stage);
  }

  /** Runs the handler; returns true if the document advanced. */
  private boolean succeed(DocumentId id, Stage stage, StageHandler handler, int runNo) {
    long started = System.nanoTime();
    try {
      DocumentState advanced =
          tx.execute(
              status -> {
                DocumentState output = handler.advance(id, runNo);
                if (!stage.outputs().contains(output)) {
                  throw new IllegalStateException("stage " + stage + " cannot move to " + output);
                }
                runs.lockDocument(id);
                if (!runs.endRun(id, stage, runNo, null)) {
                  // A reprocess superseded this run while it ran; drop its outputs.
                  status.setRollbackOnly();
                  log.info("stage run superseded");
                  return null;
                }
                runs.setState(id, output);
                return output;
              });
      ended(stage, advanced == null ? SUPERSEDED : SUCCEEDED, started);
      if (advanced != null && stage == Stage.VALIDATE) {
        // The Validate stage's output is the VAL-8 decision (ADR-20).
        metrics.decided(advanced.name());
      }
      return advanced != null;
    } catch (StageFailedException e) {
      failed(id, stage, runNo, e.code(), started);
    } catch (RuntimeException e) {
      // Only the type: messages of library exceptions may quote document content (PII-8).
      log.atWarn().addKeyValue("exception", e.getClass().getName()).log("stage error");
      failed(id, stage, runNo, UNEXPECTED_ERROR, started);
    }
    return false;
  }

  private void failed(DocumentId id, Stage stage, int runNo, String code, long started) {
    if (!fail(id, stage, runNo, code)) {
      ended(stage, SUPERSEDED, started);
      return;
    }
    ended(stage, FAILED, started);
    if (isFailedDecision(stage, code)) {
      metrics.decided(DecisionOutcome.FAILED.name());
    }
  }

  /**
   * The failures that are the {@code FAILED} decision of architecture.md §6.6: a PII leak in the
   * model response and a missing extraction. Other failures are stage errors, not decisions.
   */
  static boolean isFailedDecision(Stage stage, String code) {
    return (stage == Stage.EXTRACT && LeakChecker.FAILURE_CODE.equals(code))
        || (stage == Stage.VALIDATE && ValidateStageHandler.EXTRACTION_MISSING.equals(code));
  }

  private void ended(Stage stage, String outcome, long started) {
    metrics.stageEnded(stage.name(), outcome, Duration.ofNanos(System.nanoTime() - started));
  }

  /** Marks the run and document failed; returns false if a reprocess superseded the run. */
  private boolean fail(DocumentId id, Stage stage, int runNo, String code) {
    return Boolean.TRUE.equals(
        tx.execute(
            _ -> {
              runs.lockDocument(id);
              if (!runs.endRun(id, stage, runNo, code)) {
                return false;
              }
              runs.setFailed(id, stage, code);
              log.atWarn().addKeyValue("failureCode", code).log("stage failed");
              return true;
            }));
  }

  private void submit(DocumentId id, Stage stage) {
    jobs.enqueue(new StageJob(id.value(), stage));
  }
}
