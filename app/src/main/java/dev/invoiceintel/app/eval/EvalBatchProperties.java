package dev.invoiceintel.app.eval;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code invoice-intel.eval-batch.*} (architecture.md §10): set {@code input} to run the eval batch
 * at start-up instead of serving. Both are unset in every profile; the owner passes them on the
 * command line.
 *
 * @param input folder of originals (PDF or image), read only
 * @param output folder for the {@code <sha256>.json} result files; git-ignored, since results of
 *     real documents hold extracted values (.claude/rules/pii.md)
 */
@ConfigurationProperties("invoice-intel.eval-batch")
public record EvalBatchProperties(Path input, Path output) {}
