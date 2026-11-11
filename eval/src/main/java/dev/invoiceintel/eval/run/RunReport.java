package dev.invoiceintel.eval.run;

import dev.invoiceintel.eval.metrics.fields.FieldCounts;
import dev.invoiceintel.eval.metrics.fields.HeaderField;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/**
 * The Markdown report of a run ({@code report.md}, EVAL-1): configuration (EVAL-4), document
 * counts, the EVAL-2 metrics beside the spec §9 / NFR-3 / NFR-4 targets, and per-field accuracy.
 * Counts, rates, amounts and field names only.
 */
public final class RunReport {

  /** Shown for a metric that has no value in this run (nothing ran, no rate given). */
  static final String NONE = "n/a";

  static final String HEADER_ACCURACY = "Header field accuracy";
  static final String LINE_ITEM_F1 = "Line-item F1";
  static final String STRAIGHT_THROUGH = "Straight-through rate";
  static final String FALSE_AUTO_APPROVAL = "False auto-approval rate";
  static final String COST_USD = "Cost per document (USD)";
  static final String COST_INR = "Cost per document (INR)";
  static final String P50 = "Latency p50";
  static final String P95 = "Latency p95";

  private static final Map<String, String> TARGETS =
      Map.of(
          HEADER_ACCURACY, "≥ 95% digital, ≥ 85% scan/photo (§9)",
          LINE_ITEM_F1, "≥ 0.90 digital (§9)",
          STRAIGHT_THROUGH, "≥ 70% (§9)",
          FALSE_AUTO_APPROVAL, "≤ 2% (§9)",
          COST_INR, "≤ ₹1 (NFR-4)",
          P95, "≤ 20 s digital PDF (NFR-3)");

  private RunReport() {}

  /** The report text. */
  public static String markdown(RunConfig config, RunSummary summary) {
    StringBuilder md = new StringBuilder(2048);
    md.append("# Eval run ").append(config.runId()).append("\n\n");
    md.append("| Setting | Value |\n|---|---|\n");
    row(md, "Started", config.startedAt().toString());
    row(md, "Dataset", config.dataset());
    row(md, "Strategy", config.strategy());
    row(md, "Model", config.model());
    row(md, "Runner", config.runner());
    row(md, "Git SHA", config.gitSha());
    row(md, "Model IDs called", list(config.modelIds()));
    row(md, "Prompt versions", list(config.promptVersions()));
    row(md, "USD to INR", config.usdInr() == null ? NONE : config.usdInr().toPlainString());
    md.append("\n## Documents\n\n| Status | Count |\n|---|---|\n");
    row(md, "Total", Integer.toString(summary.documents()));
    row(md, "Extracted", Integer.toString(summary.extracted()));
    row(md, "Failed", Integer.toString(summary.failed()));
    row(md, "Skipped", Integer.toString(summary.skipped()));
    row(md, "Auto-approved", Integer.toString(summary.autoApproved()));
    row(md, "Needs review", Integer.toString(summary.needsReview()));
    md.append("\n## Metrics\n\n| Metric | Value | Target |\n|---|---|---|\n");
    Map<String, String> metrics = metrics(summary, config.usdInr());
    for (Map.Entry<String, String> e : metrics.entrySet()) {
      md.append("| ")
          .append(e.getKey())
          .append(" | ")
          .append(e.getValue())
          .append(" | ")
          .append(TARGETS.getOrDefault(e.getKey(), ""))
          .append(" |\n");
    }
    md.append(
        "\nRates are over documents the runner ran (extracted or failed); field metrics over"
            + " extracted documents. A document is correct when every header field and line item"
            + " matches its label. Latency is the runner's wall time per document.\n");
    md.append("\n## Field accuracy\n\n| Field | Correct | Total | Accuracy |\n|---|---|---|---|\n");
    for (HeaderField field : HeaderField.values()) {
      FieldCounts counts = summary.perField().get(field);
      md.append("| ")
          .append(field.labelKey())
          .append(" | ")
          .append(counts.correct())
          .append(" | ")
          .append(counts.total())
          .append(" | ")
          .append(counts.total() == 0 ? NONE : percent(counts.accuracy()))
          .append(" |\n");
    }
    return md.toString();
  }

  /** The EVAL-2 metrics by display name, in report order; {@code compare} prints the same. */
  public static Map<String, String> metrics(RunSummary summary, BigDecimal usdInr) {
    Map<String, String> m = new LinkedHashMap<>();
    m.put(HEADER_ACCURACY, percent(summary.headerAccuracy()));
    m.put(
        LINE_ITEM_F1,
        summary.extracted() == 0
            ? NONE
            : String.format(
                Locale.ROOT,
                "%.4f (P %.4f, R %.4f)",
                summary.lineItems().f1(),
                summary.lineItems().precision(),
                summary.lineItems().recall()));
    m.put(STRAIGHT_THROUGH, percent(summary.straightThroughRate()));
    m.put(FALSE_AUTO_APPROVAL, percent(summary.falseAutoApprovalRate()));
    BigDecimal usd = summary.costPerDocumentUsd();
    m.put(COST_USD, usd == null ? NONE : "$" + usd.toPlainString());
    BigDecimal inr = summary.costPerDocumentInr(usdInr);
    m.put(COST_INR, inr == null ? NONE : "₹" + inr.toPlainString());
    m.put(P50, millis(summary.p50LatencyMs()));
    m.put(P95, millis(summary.p95LatencyMs()));
    return m;
  }

  private static void row(StringBuilder md, String name, String value) {
    md.append("| ").append(name).append(" | ").append(value).append(" |\n");
  }

  private static String list(List<String> values) {
    return values.isEmpty() ? "none" : String.join(", ", values);
  }

  private static String percent(OptionalDouble rate) {
    return rate.isPresent() ? percent(rate.getAsDouble()) : NONE;
  }

  private static String percent(double rate) {
    return String.format(Locale.ROOT, "%.1f%%", rate * 100);
  }

  private static String millis(OptionalLong ms) {
    return ms.isPresent() ? ms.getAsLong() + " ms" : NONE;
  }
}
