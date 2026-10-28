package dev.invoiceintel.pii;

import com.google.crypto.tink.Aead;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Objects;

/**
 * Encrypts the real value behind each token with Tink AEAD, using the token as associated data, so
 * a ciphertext only opens under the token it was written for (PII-3, PII-7, architecture.md §6.3).
 * Build it with {@link PiiKeys#vault}.
 */
public final class PiiVault {

  private final Aead aead;

  PiiVault(Aead aead) {
    this.aead = Objects.requireNonNull(aead, "aead");
  }

  /** Encrypts {@code value} for {@code token}. */
  public VaultEntry seal(String token, PiiType type, String value) {
    Objects.requireNonNull(token, "token");
    Objects.requireNonNull(value, "value");
    try {
      byte[] ciphertext =
          aead.encrypt(value.getBytes(StandardCharsets.UTF_8), associatedData(token));
      return new VaultEntry(token, type, ciphertext);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("vault encryption failed", e);
    }
  }

  /**
   * Decrypts an entry. Fails if the ciphertext was changed, moved to another token or written with
   * another key.
   */
  public String open(VaultEntry entry) {
    Objects.requireNonNull(entry, "entry");
    try {
      byte[] plain = aead.decrypt(entry.ciphertext(), associatedData(entry.token()));
      return new String(plain, StandardCharsets.UTF_8);
    } catch (GeneralSecurityException e) {
      // No token or value in the message (PII-8).
      throw new IllegalStateException("vault entry could not be decrypted", e);
    }
  }

  private static byte[] associatedData(String token) {
    return token.getBytes(StandardCharsets.UTF_8);
  }
}
