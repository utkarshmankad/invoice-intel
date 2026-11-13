package dev.invoiceintel.eval.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.invoiceintel.eval.metrics.fields.HeaderField;
import dev.invoiceintel.eval.metrics.fields.LineItemCounts;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunDirectoryTest {

  private static final RunConfig CONFIG =
      new RunConfig(
          "2026-11-03-a",
          Instant.parse("2026-11-03T10:15:30Z"),
          "data/labels",
          "TEXT",
          "claude-text-default",
          "stub",
          "0123456789abcdef0123456789abcdef01234567",
          List.of("model-a"),
          List.of("extract-text/v1"),
          new BigDecimal("84.50"));

  private static final List<DocumentRecord> RECORDS =
      List.of(
          new DocumentRecord(
              "a".repeat(64),
              true,
              DocumentResult.Status.EXTRACTED,
              Decision.AUTO_APPROVED,
              Set.of(HeaderField.GRAND_TOTAL, HeaderField.INVOICE_DATE),
              new LineItemCounts(1, 0, 1),
              new BigDecimal("0.002400"),
              1200,
              ""),
          new DocumentRecord(
              "c".repeat(64),
              true,
              DocumentResult.Status.EXTRACTED,
              Decision.NEEDS_REVIEW,
              Set.of(),
              new LineItemCounts(2, 0, 0),
              new BigDecimal("0.001000"),
              900,
              ""),
          new DocumentRecord(
              "b".repeat(64),
              false,
              DocumentResult.Status.FAILED,
              Decision.FAILED,
              null,
              null,
              BigDecimal.ZERO,
              30,
              "IllegalStateException"),
          new DocumentRecord(
              "d".repeat(64),
              false,
              DocumentResult.Status.SKIPPED,
              null,
              null,
              null,
              BigDecimal.ZERO,
              0,
              ""));

  @TempDir Path dir;

  @Test
  void writeThenRead_roundTrips() {
    Path run = dir.resolve("reports/2026-11-03-a");

    new RunDirectory(CONFIG, RECORDS).write(run);
    RunDirectory read = RunDirectory.read(run);

    assertThat(read.config()).isEqualTo(CONFIG);
    assertThat(read.documents()).isEqualTo(RECORDS);
  }

  @Test
  void writeThenRead_withReconciliation_roundTripsAndReportsMatchRate() throws IOException {
    Path run = dir.resolve("reports/rec");
    var counts = ReconciliationCountsTest.sample();

    new RunDirectory(CONFIG, RECORDS, counts).write(run);
    RunDirectory read = RunDirectory.read(run);

    assertThat(read.reconciliation()).isEqualTo(counts);
    assertThat(Files.readString(run.resolve("report.md")))
        .contains("## Reconciliation")
        .contains("| Reconciliation match rate | 75.0% | ≥ 95% (§9) |")
        .contains("| ORDER_WITHOUT_INVOICE | 2 |");
  }

  @Test
  void write_withoutReconciliation_hasNoSectionOrFile() throws IOException {
    Path run = dir.resolve("reports/none");

    new RunDirectory(CONFIG, RECORDS).write(run);

    assertThat(run.resolve(ReconciliationCounts.FILE)).doesNotExist();
    assertThat(Files.readString(run.resolve("report.md"))).doesNotContain("Reconciliation");
    assertThat(RunDirectory.read(run).reconciliation()).isNull();
  }

  @Test
  void write_recordsConfigCsvsAndReport() throws IOException {
    Path run = dir.resolve("run");

    new RunDirectory(CONFIG, RECORDS).write(run);

    assertThat(Files.readString(run.resolve(RunDirectory.CONFIG)))
        .contains(
            "\"runId\" : \"2026-11-03-a\"",
            "\"strategy\" : \"TEXT\"",
            "\"model\" : \"claude-text-default\"",
            "\"runner\" : \"stub\"",
            "\"gitSha\" : \"0123456789abcdef0123456789abcdef01234567\"",
            "\"modelIds\" : [ \"model-a\" ]",
            "\"promptVersions\" : [ \"extract-text/v1\" ]",
            "\"usdInr\" : \"84.50\"",
            "\"documents\" : 4");
    assertThat(Files.readAllLines(run.resolve(RunDirectory.DOCUMENTS)))
        .containsExactly(
            RunDirectory.CSV_HEADER,
            "a".repeat(64)
                + ",true,EXTRACTED,AUTO_APPROVED,false,invoiceDate;grandTotal,1,0,1,0.002400,1200,",
            "c".repeat(64) + ",true,EXTRACTED,NEEDS_REVIEW,true,,2,0,0,0.001000,900,",
            "b".repeat(64) + ",false,FAILED,FAILED,,,,,,0,30,IllegalStateException",
            "d".repeat(64) + ",false,SKIPPED,,,,,,,0,0,");
    assertThat(Files.readAllLines(run.resolve(RunDirectory.FIELDS)))
        .startsWith(RunDirectory.FIELDS_HEADER, "documentClass,2,2,1.0000")
        .contains("invoiceDate,1,2,0.5000", "grandTotal,1,2,0.5000");
    assertThat(Files.readString(run.resolve(RunDirectory.REPORT)))
        .contains(
            "# Eval run 2026-11-03-a",
            "| Git SHA | 0123456789abcdef0123456789abcdef01234567 |",
            "| Header field accuracy | 94.7% |",
            "| False auto-approval rate | 33.3% | ≤ 2% (§9) |",
            "| Straight-through rate | 0.0% | ≥ 70% (§9) |",
            "| Cost per document (USD) | $0.001133 |",
            "| Cost per document (INR) | ₹0.10 | ≤ ₹1 (NFR-4) |",
            "| Latency p95 | 1200 ms |",
            "| grandTotal | 1 | 2 | 50.0% |");
  }

  @Test
  void write_withoutRateOrRanDocuments_reportsNotAvailable() throws IOException {
    RunConfig config =
        new RunConfig(
            "r",
            CONFIG.startedAt(),
            "d",
            "TEXT",
            "m",
            "stub",
            "unknown",
            List.of(),
            List.of(),
            null);
    Path run = dir.resolve("run");

    new RunDirectory(config, List.of(RECORDS.get(3))).write(run);

    assertThat(Files.readString(run.resolve(RunDirectory.CONFIG))).contains("\"usdInr\" : null");
    assertThat(Files.readString(run.resolve(RunDirectory.REPORT)))
        .contains(
            "| Model IDs called | none |",
            "| Header field accuracy | n/a |",
            "| Line-item F1 | n/a |",
            "| Cost per document (INR) | n/a |",
            "| Latency p50 | n/a |",
            "| documentClass | 0 | 0 | n/a |");
    assertThat(RunDirectory.read(run).config()).isEqualTo(config);
  }

  @Test
  void read_unknownFieldOrBadNumber_throws() throws IOException {
    Path run = dir.resolve("run");
    new RunDirectory(CONFIG, RECORDS).write(run);
    String row = "a".repeat(64) + ",true,EXTRACTED,AUTO_APPROVED,false,%s,1,0,%s,0,1,";

    Files.writeString(
        run.resolve(RunDirectory.DOCUMENTS),
        RunDirectory.CSV_HEADER + "\n" + row.formatted("buyerName", "0") + "\n");
    assertThatThrownBy(() -> RunDirectory.read(run))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown field");

    Files.writeString(
        run.resolve(RunDirectory.DOCUMENTS),
        RunDirectory.CSV_HEADER + "\n" + row.formatted("", "x") + "\n");
    assertThatThrownBy(() -> RunDirectory.read(run))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Malformed row");
  }

  @Test
  void read_oldHeaderOrConfigWithoutLists_throws() throws IOException {
    Path run = dir.resolve("run");
    new RunDirectory(CONFIG, RECORDS).write(run);
    Files.writeString(run.resolve(RunDirectory.DOCUMENTS), "sha256,synthetic,status\n");

    assertThatThrownBy(() -> RunDirectory.read(run))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unexpected header");

    Files.writeString(
        run.resolve(RunDirectory.CONFIG),
        Files.readString(run.resolve(RunDirectory.CONFIG)).replace("\"modelIds\"", "\"x\""));
    assertThatThrownBy(() -> RunDirectory.read(run))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("modelIds");
  }

  @Test
  void write_existingFolder_throws() throws IOException {
    Files.createDirectories(dir.resolve("run"));

    assertThatThrownBy(() -> new RunDirectory(CONFIG, RECORDS).write(dir.resolve("run")))
        .isInstanceOf(UncheckedIOException.class);
  }

  @Test
  void read_folderWithoutRunFiles_throws() {
    assertThatThrownBy(() -> RunDirectory.read(dir))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Not a run folder");
  }

  @Test
  void read_malformedRow_throws() throws IOException {
    Path run = dir.resolve("run");
    new RunDirectory(CONFIG, RECORDS).write(run);
    Files.writeString(run.resolve(RunDirectory.DOCUMENTS), RunDirectory.CSV_HEADER + "\nx,y\n\n");

    assertThatThrownBy(() -> RunDirectory.read(run))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Malformed row");
  }

  @Test
  void read_configWithoutKey_throws() throws IOException {
    Path run = dir.resolve("run");
    new RunDirectory(CONFIG, RECORDS).write(run);
    Files.writeString(run.resolve(RunDirectory.CONFIG), "{\"runId\": 1}");

    assertThatThrownBy(() -> RunDirectory.read(run))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("runId");
  }

  @Test
  void read_blankLines_areIgnored() throws IOException {
    Path run = dir.resolve("run");
    new RunDirectory(CONFIG, RECORDS).write(run);
    Files.writeString(run.resolve(RunDirectory.DOCUMENTS), RunDirectory.CSV_HEADER + "\n\n");

    assertThat(RunDirectory.read(run).documents()).isEmpty();
  }
}
