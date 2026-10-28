package dev.invoiceintel.pii;

/** How {@link TextRedactor} replaces PII (PII-3, specs.md §6.1). */
public enum RedactionMode {
  /** Reversible: values become deterministic tokens and their real values go to the vault. */
  PSEUDONYMIZE,
  /** Irreversible: values become {@code [REDACTED:TYPE]}; no vault entries are written. */
  REDACT
}
