package dev.invoiceintel.eval.cli;

import dev.invoiceintel.eval.run.Dataset;
import dev.invoiceintel.eval.run.DocumentRecord;
import dev.invoiceintel.eval.run.DocumentResult;
import dev.invoiceintel.eval.run.DocumentRunner;
import dev.invoiceintel.eval.run.EvalRun;
import dev.invoiceintel.eval.run.RunConfig;
import dev.invoiceintel.eval.run.RunDirectory;
import dev.invoiceintel.eval.run.RunReport;
import dev.invoiceintel.eval.run.RunnerFactory;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The eval CLI (EVAL-1, architecture.md §6.9): {@code run} runs a pipeline configuration over a
 * labelled dataset and writes a run folder; {@code compare} sets two run folders side by side.
 * Output names documents by SHA-256 and label files by name only, never a value
 * (.claude/rules/pii.md). {@code run} writes the EVAL-2 metrics as {@code report.md}, {@code
 * documents.csv} and {@code fields.csv} beside {@code config.json} (M5.5).
 */
@Command(
    name = "eval",
    mixinStandardHelpOptions = true,
    description = "Invoice Intel evaluation harness (EVAL-1).")
public final class EvalCli {

  /** Exit code when the dataset has invalid labels or a run folder can't be read or written. */
  public static final int FAILED = 1;

  /**
   * Strategy names of {@code dev.invoiceintel.extract.ExtractionStrategy}. {@code eval} doesn't
   * depend on {@code extract} yet; the pipeline runner will parse the name with that enum.
   */
  static final List<String> STRATEGIES = List.of("TEXT", "VISION", "HYBRID");

  private static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
  private static final DateTimeFormatter RUN_ID_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC);

  private final RunnerFactory runners;
  private final Supplier<String> gitSha;
  private final Clock clock;
  private final PrintWriter out;
  private final PrintWriter err;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "The CLI prints to the caller's writers by design")
  public EvalCli(
      RunnerFactory runners,
      Supplier<String> gitSha,
      Clock clock,
      PrintWriter out,
      PrintWriter err) {
    this.runners = runners;
    this.gitSha = gitSha;
    this.clock = clock;
    this.out = out;
    this.err = err;
  }

  /** A picocli command line for this CLI, printing to its writers. */
  public CommandLine commandLine() {
    return new CommandLine(this).setOut(out).setErr(err);
  }

  /** Runs the CLI and returns the exit code. */
  public int execute(String... args) {
    return commandLine().execute(args);
  }

  @Command(
      name = "run",
      mixinStandardHelpOptions = true,
      description = "Runs a pipeline configuration over a labelled dataset.")
  int run(
      @Option(
              names = "--dataset",
              required = true,
              paramLabel = "DIR",
              description = "Folder of label files <sha256>.json.")
          Path dataset,
      @Option(
              names = "--strategy",
              required = true,
              paramLabel = "STRATEGY",
              description = "Extraction strategy: TEXT, VISION or HYBRID.")
          String strategy,
      @Option(
              names = "--model",
              defaultValue = "default",
              paramLabel = "MODEL",
              description = "Model ID (default: ${DEFAULT-VALUE}).")
          String model,
      @Option(
              names = "--out",
              defaultValue = "eval-reports",
              paramLabel = "DIR",
              description = "Folder for run folders (default: ${DEFAULT-VALUE}).")
          Path outDir,
      @Option(
              names = "--run-id",
              paramLabel = "ID",
              description = "Run folder name (default: UTC start time, yyyy-MM-dd-HHmmss).")
          String runId,
      @Option(
              names = "--usd-inr",
              paramLabel = "RATE",
              description =
                  "Rupees per US dollar, to report cost per document in INR (NFR-4); recorded in"
                      + " config.json.")
          BigDecimal usdInr) {
    String strategyName = strategy.toUpperCase(Locale.ROOT);
    if (!STRATEGIES.contains(strategyName)) {
      err.println("Unknown strategy " + strategy + "; use one of " + STRATEGIES);
      return CommandLine.ExitCode.USAGE;
    }
    Instant startedAt = clock.instant();
    String id = runId == null ? RUN_ID_FORMAT.format(startedAt) : runId;
    if (!RUN_ID.matcher(id).matches()) {
      err.println("Run ID must be 1-64 letters, digits, '.', '_' or '-', starting alphanumeric");
      return CommandLine.ExitCode.USAGE;
    }
    if (usdInr != null && usdInr.signum() <= 0) {
      err.println("--usd-inr must be positive");
      return CommandLine.ExitCode.USAGE;
    }
    if (!Files.isDirectory(dataset)) {
      err.println("Not a folder: " + dataset);
      return FAILED;
    }
    Path runDir = outDir.resolve(id);
    if (Files.exists(runDir)) {
      err.println("Run folder already exists: " + runDir);
      return FAILED;
    }

    Dataset data = Dataset.load(dataset);
    if (!data.invalid().isEmpty()) {
      data.invalid()
          .forEach(
              (name, errors) -> {
                err.println(name + ":");
                errors.forEach(e -> err.println("  " + e));
              });
      err.println(
          data.invalid().size() + " invalid label files; fix them before running (checkLabels)");
      return FAILED;
    }

    DocumentRunner runner = runners.create(strategyName, model);
    EvalRun.Result result = EvalRun.execute(data.documents(), runner);
    List<DocumentRecord> records = result.records();
    RunConfig config =
        new RunConfig(
            id,
            startedAt,
            dataset.toString(),
            strategyName,
            model,
            runner.name(),
            gitSha.get(),
            List.copyOf(new TreeSet<>(result.usage().modelIds())),
            List.copyOf(new TreeSet<>(result.usage().promptVersions())),
            usdInr);
    new RunDirectory(config, records).write(runDir);

    out.printf(
        "Run %s: %d documents (%s) with runner %s, strategy %s, model %s%n",
        id, records.size(), counts(records), runner.name(), strategyName, model);
    out.println("Wrote " + runDir);
    return CommandLine.ExitCode.OK;
  }

  @Command(
      name = "compare",
      mixinStandardHelpOptions = true,
      description = "Compares two run folders and prints a Markdown summary.")
  int compare(
      @Parameters(index = "0", paramLabel = "RUN_A", description = "First run folder.") Path a,
      @Parameters(index = "1", paramLabel = "RUN_B", description = "Second run folder.") Path b) {
    RunDirectory runA;
    RunDirectory runB;
    try {
      runA = RunDirectory.read(a);
      runB = RunDirectory.read(b);
    } catch (IllegalArgumentException e) {
      err.println(e.getMessage());
      return FAILED;
    }
    RunConfig ca = runA.config();
    RunConfig cb = runB.config();
    out.println("# Eval comparison: " + ca.runId() + " vs " + cb.runId());
    out.println();
    out.println("| | " + ca.runId() + " | " + cb.runId() + " |");
    out.println("|---|---|---|");
    row("Started", ca.startedAt().toString(), cb.startedAt().toString());
    row("Dataset", ca.dataset(), cb.dataset());
    row("Strategy", ca.strategy(), cb.strategy());
    row("Model", ca.model(), cb.model());
    row("Runner", ca.runner(), cb.runner());
    row("Git SHA", ca.gitSha(), cb.gitSha());
    row(
        "Documents",
        Integer.toString(runA.documents().size()),
        Integer.toString(runB.documents().size()));
    Map<DocumentResult.Status, Long> sa = statusCounts(runA.documents());
    Map<DocumentResult.Status, Long> sb = statusCounts(runB.documents());
    for (DocumentResult.Status status : DocumentResult.Status.values()) {
      row(
          status.name().charAt(0) + status.name().substring(1).toLowerCase(Locale.ROOT),
          sa.get(status).toString(),
          sb.get(status).toString());
    }
    Set<String> docsA = shas(runA.documents());
    Set<String> docsB = shas(runB.documents());
    long onlyA = docsA.stream().filter(s -> !docsB.contains(s)).count();
    long onlyB = docsB.stream().filter(s -> !docsA.contains(s)).count();
    row("Only in this run", Long.toString(onlyA), Long.toString(onlyB));
    Map<String, String> ma = RunReport.metrics(runA.summary(), ca.usdInr());
    Map<String, String> mb = RunReport.metrics(runB.summary(), cb.usdInr());
    ma.forEach((name, value) -> row(name, value, mb.get(name)));
    return CommandLine.ExitCode.OK;
  }

  private void row(String name, String a, String b) {
    out.println("| " + name + " | " + a + " | " + b + " |");
  }

  private static Map<DocumentResult.Status, Long> statusCounts(List<DocumentRecord> records) {
    Map<DocumentResult.Status, Long> counts = new EnumMap<>(DocumentResult.Status.class);
    for (DocumentResult.Status status : DocumentResult.Status.values()) {
      counts.put(status, 0L);
    }
    records.forEach(r -> counts.merge(r.status(), 1L, Long::sum));
    return counts;
  }

  private static String counts(List<DocumentRecord> records) {
    return statusCounts(records).entrySet().stream()
        .map(e -> e.getValue() + " " + e.getKey().name().toLowerCase(Locale.ROOT))
        .collect(Collectors.joining(", "));
  }

  private static Set<String> shas(List<DocumentRecord> records) {
    return records.stream().map(DocumentRecord::sha256).collect(Collectors.toSet());
  }
}
