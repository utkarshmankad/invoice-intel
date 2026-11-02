package dev.invoiceintel.eval.label;

import static org.assertj.core.api.Assertions.assertThat;

import dev.invoiceintel.eval.metrics.pii.PiiSpanType;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** The metric's span types and the label format's span types are the same list. */
class PiiSpanTypeSyncTest {

  @Test
  void piiSpanType_matchesLabelPiiTypes() {
    assertThat(Arrays.stream(PiiSpanType.values()).map(Enum::name))
        .containsExactlyElementsOf(LabelValidator.PII_TYPES);
  }
}
