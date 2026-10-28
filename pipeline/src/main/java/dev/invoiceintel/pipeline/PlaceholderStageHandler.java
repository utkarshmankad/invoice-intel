package dev.invoiceintel.pipeline;

import dev.invoiceintel.common.DocumentId;
import java.util.Objects;

/**
 * A stage that writes nothing and always succeeds, so the state machine runs end to end before the
 * real stage exists (plan M1.4). Tests use it for stages that don't exist yet.
 */
public final class PlaceholderStageHandler implements StageHandler {

  private final Stage stage;

  public PlaceholderStageHandler(Stage stage) {
    this.stage = Objects.requireNonNull(stage, "stage");
  }

  @Override
  public Stage stage() {
    return stage;
  }

  @Override
  public void run(DocumentId documentId, int runNo) {
    // Nothing to do: the pipeline records the run and advances the document.
  }
}
