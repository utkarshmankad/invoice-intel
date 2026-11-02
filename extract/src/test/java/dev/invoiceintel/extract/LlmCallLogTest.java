package dev.invoiceintel.extract;

import static org.assertj.core.api.Assertions.assertThat;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.common.metrics.InvoiceMetrics;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LlmCallLogTest {

  @Test
  void metered_call_isLoggedThenReportedWithProviderModelTokensAndCost() {
    var events = new ArrayList<String>();
    List<LlmCall> logged = new ArrayList<>();
    var metrics =
        new InvoiceMetrics() {
          @Override
          public void llmCall(
              String provider, String model, long input, long output, BigDecimal cost) {
            events.add(provider + " " + model + " " + input + " " + output + " " + cost);
          }
        };
    LlmCallLog log = LlmCallLog.metered(logged::add, metrics);
    var call =
        new LlmCall(
            DocumentId.random(),
            "EXTRACT",
            "anthropic",
            "claude-test",
            "extract-invoice/v1",
            1,
            1200,
            300,
            new BigDecimal("0.0081"),
            950,
            LlmOutcome.SUCCEEDED);

    log.record(call);

    assertThat(logged).containsExactly(call);
    assertThat(events).containsExactly("anthropic claude-test 1200 300 0.0081");
  }
}
