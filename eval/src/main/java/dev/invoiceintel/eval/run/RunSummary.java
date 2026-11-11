package dev.invoiceintel.eval.run;

import dev.invoiceintel.eval.metrics.fields.FieldCounts;
import dev.invoiceintel.eval.metrics.fields.HeaderField;
import dev.invoiceintel.eval.metrics.fields.LineItemCounts;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/**
 * The run-level metrics of EVAL-2 computed from a run's {@link DocumentRecord}s: field accuracy and
 * line-item F1 over the extracted documents, decision metrics, cost (NFR-4) and latency (NFR-3)
 * over the documents the runner ran. Skipped documents count in {@code documents} only. Counts,
 * rates and amounts only, no values.
 *
 * <p>Definitions (architecture.md §6.9): a document is <b>ran</b> when its status is {@code
 * EXTRACTED} or {@code FAILED}. It is <b>correct</b> when every header field and line item matched
 * its label ({@link DocumentRecord#correct()}). The <b>straight-through rate</b> is
 * auto-approved-and-correct documents over ran documents, the <b>false auto-approval rate</b>
 * auto-approved-but-wrong documents over ran documents (spec §9). Cost per document is the total
 * model cost over ran documents. Latency percentiles use the nearest-rank method over the ran
 * documents' runner wall times.
 */
public record RunSummary(
    int documents,
    int skipped,
    int extracted,
    int failed,
    Map<HeaderField, FieldCounts> perField,
    FieldCounts header,
    LineItemCounts lineItems,
    int autoApproved,
    int needsReview,
    int straightThrough,
    int falseAutoApprovals,
    BigDecimal totalCostUsd,
    OptionalLong p50LatencyMs,
    OptionalLong p95LatencyMs) {

  /** Scale of per-document USD costs ({@code llm_call.cost_usd} is {@code NUMERIC(12,6)}). */
  static final int USD_SCALE = 6;

  public RunSummary {
    perField = Collections.unmodifiableMap(new EnumMap<>(perField));
  }

  public static RunSummary of(List<DocumentRecord> records) {
    int skipped = 0;
    int extracted = 0;
    int failed = 0;
    Map<HeaderField, FieldCounts> perField = new EnumMap<>(HeaderField.class);
    for (HeaderField field : HeaderField.values()) {
      perField.put(field, FieldCounts.ZERO);
    }
    LineItemCounts lineItems = LineItemCounts.ZERO;
    int autoApproved = 0;
    int needsReview = 0;
    int straightThrough = 0;
    int falseAutoApprovals = 0;
    BigDecimal cost = BigDecimal.ZERO;
    long[] latencies = new long[records.size()];
    int ran = 0;
    for (DocumentRecord r : records) {
      switch (r.status()) {
        case SKIPPED -> skipped++;
        case EXTRACTED -> extracted++;
        case FAILED -> failed++;
      }
      if (r.status() == DocumentResult.Status.SKIPPED) {
        continue;
      }
      latencies[ran++] = r.latencyMs();
      cost = cost.add(r.costUsd());
      if (r.scored()) {
        for (HeaderField field : HeaderField.values()) {
          perField.put(field, perField.get(field).plus(!r.mismatchedFields().contains(field)));
        }
        lineItems = lineItems.plus(r.lineItems());
      }
      if (r.decision() == Decision.AUTO_APPROVED) {
        autoApproved++;
        if (r.correct()) {
          straightThrough++;
        } else {
          falseAutoApprovals++;
        }
      } else if (r.decision() == Decision.NEEDS_REVIEW) {
        needsReview++;
      }
    }
    FieldCounts header = perField.values().stream().reduce(FieldCounts.ZERO, FieldCounts::plus);
    long[] sorted = Arrays.copyOf(latencies, ran);
    Arrays.sort(sorted);
    return new RunSummary(
        records.size(),
        skipped,
        extracted,
        failed,
        perField,
        header,
        lineItems,
        autoApproved,
        needsReview,
        straightThrough,
        falseAutoApprovals,
        cost,
        percentile(sorted, 50),
        percentile(sorted, 95));
  }

  /** Documents the runner ran: extracted or failed. */
  public int ran() {
    return extracted + failed;
  }

  /** Auto-approved and correct over ran documents; empty if none ran. */
  public OptionalDouble straightThroughRate() {
    return rate(straightThrough);
  }

  /** Auto-approved but wrong over ran documents; empty if none ran. */
  public OptionalDouble falseAutoApprovalRate() {
    return rate(falseAutoApprovals);
  }

  /** Header field accuracy over extracted documents; empty if none was extracted. */
  public OptionalDouble headerAccuracy() {
    return header.total() == 0 ? OptionalDouble.empty() : OptionalDouble.of(header.accuracy());
  }

  /** Total model cost over ran documents, in USD; {@code null} if none ran. */
  public BigDecimal costPerDocumentUsd() {
    return ran() == 0
        ? null
        : totalCostUsd.divide(BigDecimal.valueOf(ran()), USD_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * {@link #costPerDocumentUsd()} in rupees at {@code usdInr} rupees per dollar, to the paisa;
   * {@code null} if none ran or no rate is given.
   */
  public BigDecimal costPerDocumentInr(BigDecimal usdInr) {
    BigDecimal usd = costPerDocumentUsd();
    return usd == null || usdInr == null
        ? null
        : usd.multiply(usdInr).setScale(2, RoundingMode.HALF_UP);
  }

  private OptionalDouble rate(int count) {
    return ran() == 0 ? OptionalDouble.empty() : OptionalDouble.of((double) count / ran());
  }

  /** Nearest-rank percentile of sorted values. */
  static OptionalLong percentile(long[] sorted, int percent) {
    if (sorted.length == 0) {
      return OptionalLong.empty();
    }
    int rank = (int) Math.ceil(percent / 100.0 * sorted.length);
    return OptionalLong.of(sorted[Math.max(rank, 1) - 1]);
  }
}
