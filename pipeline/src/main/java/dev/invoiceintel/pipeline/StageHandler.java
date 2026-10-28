package dev.invoiceintel.pipeline;

import dev.invoiceintel.common.DocumentId;

/**
 * The work of one stage. {@link PipelineService} calls it inside the transaction that marks the
 * stage run as succeeded and advances the document (architecture.md §5.1), so database outputs and
 * the status change commit together.
 */
public interface StageHandler {

  /** The stage this handler implements; one handler per stage. */
  Stage stage();

  /**
   * Runs the stage for one document. Outputs are keyed by {@code documentId}, stage and {@code
   * runNo}, so a re-run writes new outputs instead of overwriting the old ones.
   *
   * @throws StageFailedException for an expected failure; the document becomes {@code FAILED}
   */
  void run(DocumentId documentId, int runNo);
}
