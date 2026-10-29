package dev.invoiceintel.pii;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PiiSettingsTest {

  @Test
  void new_onlyLocalLlmEnabled_isRejected() {
    var localLlm = new PiiSettings.LocalLlm(true, URI.create("http://127.0.0.1:11434"));

    assertThatThrownBy(
            () ->
                new PiiSettings(
                    RedactionMode.PSEUDONYMIZE, false, false, PiiSettings.Ner.off(), localLlm))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least one");
  }

  @Test
  void new_onlyNerEnabled_isAccepted() {
    var ner = new PiiSettings.Ner(true, Path.of("/models/ner"), 0.5);

    var settings =
        new PiiSettings(RedactionMode.REDACT, false, false, ner, PiiSettings.LocalLlm.off());

    assertThat(settings.ner().enabled()).isTrue();
    assertThat(settings.mode()).isEqualTo(RedactionMode.REDACT);
  }

  @Test
  void new_nullMode_isRejected() {
    assertThatThrownBy(
            () ->
                new PiiSettings(
                    null, true, true, PiiSettings.Ner.off(), PiiSettings.LocalLlm.off()))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void ner_enabledWithoutModelDir_isRejected() {
    assertThatThrownBy(() -> new PiiSettings.Ner(true, null, 0.5))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("model directory");
  }

  @Test
  void ner_disabledWithoutModelDir_isAccepted() {
    assertThat(new PiiSettings.Ner(false, null, 0.5).enabled()).isFalse();
  }

  @ParameterizedTest
  @ValueSource(doubles = {0.0, -0.1, 1.01, Double.NaN})
  void ner_thresholdOutOfRange_isRejected(double threshold) {
    assertThatThrownBy(() -> new PiiSettings.Ner(false, null, threshold))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("threshold");
  }

  @Test
  void localLlm_enabledWithRemoteEndpoint_isRejected() {
    assertThatThrownBy(
            () -> new PiiSettings.LocalLlm(true, URI.create("https://llm.example.com/v1")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("loopback");
  }

  @Test
  void localLlm_disabledWithRemoteEndpoint_isAccepted() {
    assertThat(new PiiSettings.LocalLlm(false, URI.create("https://llm.example.com")).enabled())
        .isFalse();
  }
}
