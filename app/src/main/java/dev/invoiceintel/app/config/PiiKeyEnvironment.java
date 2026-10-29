package dev.invoiceintel.app.config;

import java.util.Map;

/**
 * The variables {@link dev.invoiceintel.pii.PiiKeys} reads the PII keysets from (PII-7). The app
 * defines no such bean, so the keysets come from the process environment; tests register one with
 * throwaway keysets built in-process.
 *
 * @param variables at least {@code TOKEN_KEYSET} and {@code VAULT_KEYSET}
 */
public record PiiKeyEnvironment(Map<String, String> variables) {

  public PiiKeyEnvironment {
    variables = Map.copyOf(variables);
  }

  @Override
  public String toString() {
    return "PiiKeyEnvironment[variables=" + variables.keySet() + "]";
  }
}
