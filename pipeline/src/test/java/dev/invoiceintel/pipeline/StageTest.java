package dev.invoiceintel.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class StageTest {

  @ParameterizedTest
  @EnumSource(value = Stage.class, names = "VALIDATE", mode = EnumSource.Mode.EXCLUDE)
  void next_followsOutputWithInput(Stage stage) {
    assertThat(stage.next())
        .hasValueSatisfying(next -> assertThat(next.input()).isEqualTo(stage.output()));
  }

  @Test
  void next_lastStage_isEmpty() {
    assertThat(Stage.VALIDATE.next()).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(Stage.class)
  void output_isLaterThanInput(Stage stage) {
    assertThat(stage.output()).isGreaterThan(stage.input());
  }

  @Test
  void text_movesReceivedToTextReady() {
    assertThat(Stage.TEXT.input()).isEqualTo(DocumentState.RECEIVED);
    assertThat(Stage.TEXT.output()).isEqualTo(DocumentState.TEXT_READY);
  }
}
