package dev.invoiceintel.eval.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.invoiceintel.eval.run.RunDirectory;
import dev.invoiceintel.eval.run.StubDocumentRunner;
import dev.invoiceintel.eval.run.SyntheticDataset;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvalCliTest {

  private static final String MARKER = "Unique Marker Value 4711";
  private static final Instant NOW = Instant.parse("2026-11-03T10:15:30Z");

  @TempDir Path dir;

  private final StringWriter out = new StringWriter();
  private final StringWriter err = new StringWriter();
  private final List<String> created = new ArrayList<>();

  private final EvalCli cli =
      new EvalCli(
          (strategy, model) -> {
            created.add(strategy + "/" + model);
            return new StubDocumentRunner();
          },
          Clock.fixed(NOW, ZoneOffset.UTC),
          new PrintWriter(out, true),
          new PrintWriter(err, true));

  @Test
  void run_syntheticFixtures_writesRunFolder() {
    Path dataset = SyntheticDataset.write(dir.resolve("labels"));
    Path reports = dir.resolve("reports");

    int code =
        cli.execute(
            "run",
            "--dataset",
            dataset.toString(),
            "--strategy",
            "text",
            "--model",
            "claude-text-default",
            "--out",
            reports.toString());

    assertThat(code).isZero();
    assertThat(created).containsExactly("TEXT/claude-text-default");
    RunDirectory run = RunDirectory.read(reports.resolve("2026-11-03-101530"));
    assertThat(run.config().strategy()).isEqualTo("TEXT");
    assertThat(run.config().runner()).isEqualTo("stub");
    assertThat(run.config().startedAt()).isEqualTo(NOW);
    assertThat(run.documents())
        .extracting(r -> r.sha256())
        .containsExactlyElementsOf(SyntheticDataset.ALL);
    assertThat(out.toString())
        .contains("Run 2026-11-03-101530: 2 documents (0 extracted, 2 skipped, 0 failed)");
  }

  @Test
  void run_explicitRunIdAndDefaultModel_usesThem() {
    Path dataset = SyntheticDataset.write(dir.resolve("labels"));

    int code = runCli(dataset, "--run-id", "baseline-a");

    assertThat(code).isZero();
    assertThat(created).containsExactly("TEXT/default");
    assertThat(dir.resolve("reports/baseline-a/config.json")).exists();
  }

  @Test
  void run_unknownStrategy_exitsTwo() {
    int code =
        cli.execute("run", "--dataset", dir.toString(), "--strategy", "OCR_ONLY", "--out", "x");

    assertThat(code).isEqualTo(2);
    assertThat(err.toString()).contains("Unknown strategy OCR_ONLY");
    assertThat(created).isEmpty();
  }

  @Test
  void run_unsafeRunId_exitsTwo() {
    int code = runCli(dir, "--run-id", "../escape");

    assertThat(code).isEqualTo(2);
    assertThat(err.toString()).contains("Run ID must be");
  }

  @Test
  void run_missingDataset_exitsOne() {
    int code = runCli(dir.resolve("nope"));

    assertThat(code).isEqualTo(EvalCli.FAILED);
    assertThat(err.toString()).contains("Not a folder");
  }

  @Test
  void run_existingRunFolder_exitsOne() throws IOException {
    Path dataset = SyntheticDataset.write(dir.resolve("labels"));
    Files.createDirectories(dir.resolve("reports/taken"));

    int code = runCli(dataset, "--run-id", "taken");

    assertThat(code).isEqualTo(EvalCli.FAILED);
    assertThat(err.toString()).contains("Run folder already exists");
  }

  @Test
  void run_invalidLabel_exitsOneWithoutValuesAndWritesNothing() throws IOException {
    Path dataset = SyntheticDataset.write(dir.resolve("labels"));
    Files.writeString(dataset.resolve("broken.json"), "{\n  " + MARKER + "\n}");

    int code = runCli(dataset, "--run-id", "r1");

    assertThat(code).isEqualTo(EvalCli.FAILED);
    assertThat(err.toString())
        .contains("broken.json:", "1 invalid label files")
        .doesNotContain(MARKER);
    assertThat(dir.resolve("reports/r1")).doesNotExist();
    assertThat(created).isEmpty();
  }

  @Test
  void run_missingRequiredOption_exitsTwo() {
    assertThat(cli.execute("run", "--strategy", "TEXT")).isEqualTo(2);
    assertThat(err.toString()).contains("--dataset");
  }

  @Test
  void noSubcommand_exitsTwo() {
    assertThat(cli.execute()).isEqualTo(2);
    assertThat(err.toString()).contains("Missing required subcommand");
  }

  @Test
  void help_listsSubcommands() {
    assertThat(cli.execute("--help")).isZero();
    assertThat(out.toString()).contains("run", "compare");
  }

  @Test
  void compare_twoRuns_printsMarkdownTable() {
    Path dataset = SyntheticDataset.write(dir.resolve("labels"));
    runCli(dataset, "--run-id", "a");
    runCli(dataset, "--run-id", "b", "--model", "other-model");
    out.getBuffer().setLength(0);

    int code =
        cli.execute(
            "compare", dir.resolve("reports/a").toString(), dir.resolve("reports/b").toString());

    assertThat(code).isZero();
    assertThat(out.toString())
        .contains(
            "# Eval comparison: a vs b",
            "| Model | default | other-model |",
            "| Documents | 2 | 2 |",
            "| Skipped | 2 | 2 |",
            "| Only in this run | 0 | 0 |");
  }

  @Test
  void compare_runsWithDifferentDocuments_countsDocumentsInOneRunOnly() throws IOException {
    Path dataset = SyntheticDataset.write(dir.resolve("labels"));
    runCli(dataset, "--run-id", "a");
    Files.delete(dataset.resolve(SyntheticDataset.TAX_INVOICE + ".json"));
    runCli(dataset, "--run-id", "b");

    cli.execute(
        "compare", dir.resolve("reports/a").toString(), dir.resolve("reports/b").toString());

    assertThat(out.toString()).contains("| Documents | 2 | 1 |", "| Only in this run | 1 | 0 |");
  }

  @Test
  void compare_notARunFolder_exitsOne() {
    int code = cli.execute("compare", dir.toString(), dir.toString());

    assertThat(code).isEqualTo(EvalCli.FAILED);
    assertThat(err.toString()).contains("Not a run folder");
  }

  private int runCli(Path dataset, String... extra) {
    List<String> args =
        new ArrayList<>(
            List.of(
                "run",
                "--dataset",
                dataset.toString(),
                "--strategy",
                "TEXT",
                "--out",
                dir.resolve("reports").toString()));
    args.addAll(List.of(extra));
    return cli.execute(args.toArray(String[]::new));
  }
}
