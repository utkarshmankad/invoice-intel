package dev.invoiceintel.app;

import com.google.crypto.tink.InsecureSecretKeyAccess;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.Parameters;
import com.google.crypto.tink.RegistryConfiguration;
import com.google.crypto.tink.TinkJsonProtoKeysetFormat;
import com.google.crypto.tink.aead.AeadConfig;
import com.google.crypto.tink.aead.PredefinedAeadParameters;
import com.google.crypto.tink.mac.HmacParameters;
import com.google.crypto.tink.mac.MacConfig;
import dev.invoiceintel.app.config.PiiKeyEnvironment;
import dev.invoiceintel.pii.PiiKeys;
import java.security.GeneralSecurityException;
import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Throwaway PII keysets for each app context (PII-7), so none is stored in code or config files.
 * Tests that start the app import this; {@code PiiConfiguration} then reads the keysets from it
 * instead of the process environment.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestPiiKeysConfiguration {

  @Bean
  PiiKeyEnvironment piiKeyEnvironment() {
    return new PiiKeyEnvironment(generate());
  }

  /** {@code TOKEN_KEYSET} ({@code HMAC_SHA256_256BITTAG_RAW}) and {@code VAULT_KEYSET}. */
  static Map<String, String> generate() {
    try {
      MacConfig.register();
      AeadConfig.register();
      return Map.of(
          PiiKeys.TOKEN_KEYSET,
          keyset(
              HmacParameters.builder()
                  .setKeySizeBytes(32)
                  .setTagSizeBytes(32)
                  .setHashType(HmacParameters.HashType.SHA256)
                  .setVariant(HmacParameters.Variant.NO_PREFIX)
                  .build()),
          PiiKeys.VAULT_KEYSET,
          keyset(PredefinedAeadParameters.AES256_GCM));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("can't generate test keysets", e);
    }
  }

  private static String keyset(Parameters parameters) throws GeneralSecurityException {
    return TinkJsonProtoKeysetFormat.serializeKeyset(
        KeysetHandle.newBuilder()
            .addEntry(
                KeysetHandle.generateEntryFromParameters(parameters).withRandomId().makePrimary())
            .build(),
        RegistryConfiguration.get(),
        InsecureSecretKeyAccess.get());
  }
}
