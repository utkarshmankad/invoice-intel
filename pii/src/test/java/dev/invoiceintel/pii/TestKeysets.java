package dev.invoiceintel.pii;

import com.google.crypto.tink.InsecureSecretKeyAccess;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.Parameters;
import com.google.crypto.tink.RegistryConfiguration;
import com.google.crypto.tink.TinkJsonProtoKeysetFormat;
import com.google.crypto.tink.aead.AeadConfig;
import com.google.crypto.tink.aead.PredefinedAeadParameters;
import com.google.crypto.tink.mac.HmacKey;
import com.google.crypto.tink.mac.HmacParameters;
import com.google.crypto.tink.mac.MacConfig;
import com.google.crypto.tink.util.SecretBytes;
import java.security.GeneralSecurityException;
import java.util.Map;

/** Throwaway Tink keysets for tests, generated per run or built from fixed test bytes. */
final class TestKeysets {

  static {
    // Key generation needs the key managers that PiiKeys registers on first use.
    try {
      MacConfig.register();
      AeadConfig.register();
    } catch (GeneralSecurityException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private TestKeysets() {}

  static HmacParameters hmacSha256(HmacParameters.Variant variant) throws GeneralSecurityException {
    return HmacParameters.builder()
        .setKeySizeBytes(32)
        .setTagSizeBytes(32)
        .setHashType(HmacParameters.HashType.SHA256)
        .setVariant(variant)
        .build();
  }

  static String newTokenKeyset() throws GeneralSecurityException {
    return generate(hmacSha256(HmacParameters.Variant.NO_PREFIX));
  }

  /** A raw HMAC-SHA256 keyset whose key is exactly {@code keyBytes}. */
  static String tokenKeyset(byte[] keyBytes) throws GeneralSecurityException {
    HmacKey key =
        HmacKey.builder()
            .setParameters(hmacSha256(HmacParameters.Variant.NO_PREFIX))
            .setKeyBytes(SecretBytes.copyFrom(keyBytes, InsecureSecretKeyAccess.get()))
            .build();
    KeysetHandle handle =
        KeysetHandle.newBuilder()
            .addEntry(KeysetHandle.importKey(key).withRandomId().makePrimary())
            .build();
    return TinkJsonProtoKeysetFormat.serializeKeyset(
        handle, RegistryConfiguration.get(), InsecureSecretKeyAccess.get());
  }

  static String newVaultKeyset() throws GeneralSecurityException {
    return generate(PredefinedAeadParameters.AES256_GCM);
  }

  static String generate(Parameters parameters) throws GeneralSecurityException {
    return TinkJsonProtoKeysetFormat.serializeKeyset(
        KeysetHandle.newBuilder()
            .addEntry(
                KeysetHandle.generateEntryFromParameters(parameters).withRandomId().makePrimary())
            .build(),
        RegistryConfiguration.get(),
        InsecureSecretKeyAccess.get());
  }

  static TokenGenerator tokens(String keysetJson) {
    return PiiKeys.tokenGenerator(Map.of(PiiKeys.TOKEN_KEYSET, keysetJson));
  }

  static PiiVault vault(String keysetJson) {
    return PiiKeys.vault(Map.of(PiiKeys.VAULT_KEYSET, keysetJson));
  }
}
