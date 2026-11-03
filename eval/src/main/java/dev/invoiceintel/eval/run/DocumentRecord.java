package dev.invoiceintel.eval.run;

/**
 * One row of a run's {@code documents.csv}: identifiers, outcome and timing only, no values
 * (.claude/rules/pii.md).
 *
 * @param sha256 the document's SHA-256
 * @param synthetic whether the label is synthetic
 * @param status the runner's outcome
 * @param latencyMs wall time of the runner call
 * @param error the exception's class name for a failure, else empty
 */
public record DocumentRecord(
    String sha256, boolean synthetic, DocumentResult.Status status, long latencyMs, String error) {}
