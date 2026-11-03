package dev.invoiceintel.eval;

import dev.invoiceintel.eval.cli.EvalCli;
import dev.invoiceintel.eval.run.StubDocumentRunner;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Clock;

/**
 * Entry point of the eval CLI (architecture.md §6.9).
 *
 * <pre>./gradlew :eval:run --args="run --dataset data/labels --strategy TEXT"</pre>
 *
 * <p>Every run uses the {@link StubDocumentRunner} until the in-process pipeline runner is wired.
 */
public final class Main {

  private Main() {}

  public static void main(String[] args) {
    PrintWriter out =
        new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
    PrintWriter err =
        new PrintWriter(new OutputStreamWriter(System.err, StandardCharsets.UTF_8), true);
    EvalCli cli =
        new EvalCli((strategy, model) -> new StubDocumentRunner(), Clock.systemUTC(), out, err);
    System.exit(cli.execute(args));
  }
}
