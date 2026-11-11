package dev.invoiceintel.eval.run;

import static org.assertj.core.api.Assertions.assertThat;

import dev.invoiceintel.eval.metrics.fields.LineItemCounts;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class RunSummaryTest {

  @Test
  void of_decisions_countsStraightThroughAndFalseAutoApprovals() {
    List<DocumentRecord> records =
        List.of(
            extracted(Decision.AUTO_APPROVED, new LineItemCounts(1, 0, 0), 100),
            extracted(Decision.AUTO_APPROVED, new LineItemCounts(1, 1, 0), 200),
            extracted(Decision.NEEDS_REVIEW, new LineItemCounts(1, 0, 0), 300),
            extracted(Decision.AUTO_APPROVED, new LineItemCounts(1, 0, 0), 400));

    RunSummary summary = RunSummary.of(records);

    assertThat(summary.autoApproved()).isEqualTo(3);
    assertThat(summary.needsReview()).isEqualTo(1);
    assertThat(summary.straightThrough()).isEqualTo(2);
    assertThat(summary.falseAutoApprovals()).isEqualTo(1);
    assertThat(summary.straightThroughRate()).hasValue(0.5);
    assertThat(summary.falseAutoApprovalRate()).hasValue(0.25);
    assertThat(summary.headerAccuracy()).hasValue(1.0);
    assertThat(summary.costPerDocumentUsd()).isEqualByComparingTo("0.01");
    assertThat(summary.costPerDocumentInr(new BigDecimal("84"))).isEqualByComparingTo("0.84");
    assertThat(summary.costPerDocumentInr(null)).isNull();
    assertThat(summary.p50LatencyMs()).hasValue(200);
    assertThat(summary.p95LatencyMs()).hasValue(400);
  }

  @Test
  void of_onlySkipped_hasNoRates() {
    DocumentRecord skipped =
        new DocumentRecord(
            "s", true, DocumentResult.Status.SKIPPED, null, null, null, BigDecimal.ZERO, 5, "");

    RunSummary summary = RunSummary.of(List.of(skipped));

    assertThat(summary.documents()).isEqualTo(1);
    assertThat(summary.skipped()).isEqualTo(1);
    assertThat(summary.ran()).isZero();
    assertThat(summary.straightThroughRate()).isEmpty();
    assertThat(summary.falseAutoApprovalRate()).isEmpty();
    assertThat(summary.headerAccuracy()).isEmpty();
    assertThat(summary.costPerDocumentUsd()).isNull();
    assertThat(summary.costPerDocumentInr(BigDecimal.TEN)).isNull();
    assertThat(summary.p50LatencyMs()).isEmpty();
  }

  @Test
  void percentile_nearestRank() {
    long[] hundred = LongStream.rangeClosed(1, 100).toArray();

    assertThat(RunSummary.percentile(hundred, 50)).hasValue(50);
    assertThat(RunSummary.percentile(hundred, 95)).hasValue(95);
    assertThat(RunSummary.percentile(new long[] {7}, 95)).hasValue(7);
    assertThat(RunSummary.percentile(new long[] {7, 9}, 0)).hasValue(7);
    assertThat(RunSummary.percentile(new long[0], 50)).isEmpty();
  }

  private static DocumentRecord extracted(
      Decision decision, LineItemCounts lineItems, long latencyMs) {
    return new DocumentRecord(
        "x",
        true,
        DocumentResult.Status.EXTRACTED,
        decision,
        Set.of(),
        lineItems,
        new BigDecimal("0.01"),
        latencyMs,
        "");
  }
}
