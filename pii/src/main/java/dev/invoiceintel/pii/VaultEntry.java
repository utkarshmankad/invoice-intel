package dev.invoiceintel.pii;

import java.util.Arrays;
import java.util.Objects;

/**
 * One {@code pii_vault} row (architecture.md §7): the encrypted real value behind a token. Only the
 * {@code pii} module creates or opens entries (§4.1, PII-7).
 *
 * @param ciphertext Tink AEAD ciphertext with the token as associated data
 */
public record VaultEntry(String token, PiiType type, byte[] ciphertext) {

  public VaultEntry {
    Objects.requireNonNull(token, "token");
    Objects.requireNonNull(type, "type");
    ciphertext = Objects.requireNonNull(ciphertext, "ciphertext").clone();
  }

  @Override
  public byte[] ciphertext() {
    return ciphertext.clone();
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof VaultEntry other
        && token.equals(other.token)
        && type == other.type
        && Arrays.equals(ciphertext, other.ciphertext);
  }

  @Override
  public int hashCode() {
    return Objects.hash(token, type, Arrays.hashCode(ciphertext));
  }

  @Override
  public String toString() {
    return "VaultEntry[token=" + token + ", type=" + type + "]";
  }
}
