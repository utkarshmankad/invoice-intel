package dev.invoiceintel.pii;

import com.google.crypto.tink.Aead;
import com.google.crypto.tink.InsecureSecretKeyAccess;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.Mac;
import com.google.crypto.tink.RegistryConfiguration;
import com.google.crypto.tink.TinkJsonProtoKeysetFormat;
import com.google.crypto.tink.aead.AeadConfig;
import com.google.crypto.tink.mac.HmacKey;
import com.google.crypto.tink.mac.HmacParameters;
import com.google.crypto.tink.mac.MacConfig;
import java.security.GeneralSecurityException;
import java.util.Map;
import java.util.Objects;

/**
 * Builds the token generator and the vault from Tink JSON keysets in environment variables, never
 * from code, config files or the database (PII-7, architecture.md §6.3).
 *
 * <p>Error messages name the variable only. Tink's own exceptions are not chained, so no keyset
 * text can reach a log through a cause.
 */
public final class PiiKeys {

  /** HMAC-SHA256 keyset without output prefix ({@code HMAC_SHA256_256BITTAG_RAW}). */
  public static final String TOKEN_KEYSET = "TOKEN_KEYSET";

  /** AEAD keyset, e.g. {@code AES256_GCM}. */
  public static final String VAULT_KEYSET = "VAULT_KEYSET";

  static {
    try {
      MacConfig.register();
      AeadConfig.register();
    } catch (GeneralSecurityException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private PiiKeys() {}

  /**
   * The token generator keyed by {@value #TOKEN_KEYSET}. The primary key must be HMAC-SHA256 with
   * no output prefix: a Tink-prefixed tag starts with the key ID, so the first 5 bytes, and with
   * them every token, would be the same for all values.
   */
  public static TokenGenerator tokenGenerator(Map<String, String> env) {
    KeysetHandle handle = keyset(env, TOKEN_KEYSET);
    if (!(handle.getPrimary().getKey() instanceof HmacKey key)
        || key.getParameters().getHashType() != HmacParameters.HashType.SHA256
        || key.getParameters().getVariant() != HmacParameters.Variant.NO_PREFIX) {
      throw new IllegalStateException(
          TOKEN_KEYSET + " must have an HMAC-SHA256 primary key without output prefix");
    }
    try {
      return new TokenGenerator(handle.getPrimitive(RegistryConfiguration.get(), Mac.class));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(TOKEN_KEYSET + " is not a usable MAC keyset");
    }
  }

  /** The vault keyed by {@value #VAULT_KEYSET}. */
  public static PiiVault vault(Map<String, String> env) {
    KeysetHandle handle = keyset(env, VAULT_KEYSET);
    try {
      return new PiiVault(handle.getPrimitive(RegistryConfiguration.get(), Aead.class));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(VAULT_KEYSET + " is not a usable AEAD keyset");
    }
  }

  private static KeysetHandle keyset(Map<String, String> env, String name) {
    String json = Objects.requireNonNull(env, "env").get(name);
    if (json == null || json.isBlank()) {
      throw new IllegalStateException(name + " is not set");
    }
    try {
      return TinkJsonProtoKeysetFormat.parseKeyset(
          json, RegistryConfiguration.get(), InsecureSecretKeyAccess.get());
    } catch (GeneralSecurityException | RuntimeException e) {
      throw new IllegalStateException(name + " is not a valid Tink JSON keyset");
    }
  }
}
