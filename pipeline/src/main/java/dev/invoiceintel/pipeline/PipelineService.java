package dev.invoiceintel.pipeline;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.common.logging.LogContext;
import dev.invoiceintel.pipeline.internal.StageRunRepository;
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
 */
public final class PipelineService {

  /** Failure code for exceptions that aren't a {@link StageFailedException}. */
  public static final String UNEXPECTED_ERROR = "UNEXPECTED_ERROR";

  private static final Logger log = LoggerFactory.getLogger(PipelineService.class);

  private final StageRunRepository runs;
  private final TransactionTemplate tx;
  private final JobRequestScheduler jobs;
  private final Map<Stage, StageHandler> handlers;

  public PipelineService(
      JdbcClient jdbc,
      PlatformTransactionManager transactionManager,
      JobRequestScheduler jobs,
      List<StageHandler> handlers) {
    this.runs = new StageRunRepository(jdbc);
    this.tx = new TransactionTemplate(Objects.requireNonNull(transactionManager, "tx"));
    this.jobs = Objects.requireNonNull(jobs, "jobs");
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
    try {
      return Boolean.TRUE.equals(
          tx.execute(
              status -> {
                handler.run(id, runNo);
                runs.lockDocument(id);
                if (!runs.endRun(id, stage, runNo, null)) {
                  // A reprocess superseded this run while it ran; drop its outputs.
                  status.setRollbackOnly();
                  log.info("stage run superseded");
                  return false;
                }
                runs.setState(id, stage.output());
                return true;
              }));
    } catch (StageFailedException e) {
      fail(id, stage, runNo, e.code());
    } catch (RuntimeException e) {
      // Only the type: messages of library exceptions may quote document content (PII-8).
      log.atWarn().addKeyValue("exception", e.getClass().getName()).log("stage error");
      fail(id, stage, runNo, UNEXPECTED_ERROR);
    }
    return false;
  }

  private void fail(DocumentId id, Stage stage, int runNo, String code) {
    tx.executeWithoutResult(
        _ -> {
          runs.lockDocument(id);
          if (runs.endRun(id, stage, runNo, code)) {
            runs.setFailed(id, stage, code);
            log.atWarn().addKeyValue("failureCode", code).log("stage failed");
          }
        });
  }

  private void submit(DocumentId id, Stage stage) {
    jobs.enqueue(new StageJob(id.value(), stage));
  }
}
