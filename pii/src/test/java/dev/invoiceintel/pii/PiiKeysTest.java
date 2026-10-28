package dev.invoiceintel.pii;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.crypto.tink.aead.PredefinedAeadParameters;
import com.google.crypto.tink.mac.HmacParameters;
import com.google.crypto.tink.mac.PredefinedMacParameters;
import java.security.GeneralSecurityException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PiiKeysTest {

  @Test
  void tokenGenerator_missingVariable_namesItWithoutValue() {
    assertThatThrownBy(() -> PiiKeys.tokenGenerator(Map.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("TOKEN_KEYSET is not set");
  }

  @Test
  void vault_missingVariable_namesItWithoutValue() {
    assertThatThrownBy(() -> PiiKeys.vault(Map.of(PiiKeys.VAULT_KEYSET, "  ")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("VAULT_KEYSET is not set");
  }

  @ParameterizedTest
  @ValueSource(strings = {"not json", "{\"primaryKeyId\": 1}", "{}"})
  void vault_malformedKeyset_rejectedWithoutCause(String json) {
    assertThatThrownBy(() -> PiiKeys.vault(Map.of(PiiKeys.VAULT_KEYSET, json)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("VAULT_KEYSET is not a valid Tink JSON keyset")
        .hasNoCause();
  }

  @Test
  void tokenGenerator_tinkPrefixedKey_rejected() throws GeneralSecurityException {
    // A Tink-prefixed tag starts with the key ID, so every token would be the same.
    String prefixed = TestKeysets.generate(PredefinedMacParameters.HMAC_SHA256_256BITTAG);

    assertThatThrownBy(() -> TestKeysets.tokens(prefixed))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("TOKEN_KEYSET must have an HMAC-SHA256 primary key without output prefix");
  }

  @Test
  void tokenGenerator_sha512Key_rejected() throws GeneralSecurityException {
    String sha512 =
        TestKeysets.generate(
            HmacParameters.builder()
                .setKeySizeBytes(32)
                .setTagSizeBytes(32)
                .setHashType(HmacParameters.HashType.SHA512)
                .setVariant(HmacParameters.Variant.NO_PREFIX)
                .build());

    assertThatThrownBy(() -> TestKeysets.tokens(sha512))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("HMAC-SHA256");
  }

  @Test
  void tokenGenerator_aeadKeyset_rejected() throws GeneralSecurityException {
    String aead = TestKeysets.newVaultKeyset();

    assertThatThrownBy(() -> TestKeysets.tokens(aead))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("TOKEN_KEYSET");
  }

  @Test
  void vault_macKeyset_rejected() throws GeneralSecurityException {
    String mac = TestKeysets.newTokenKeyset();

    assertThatThrownBy(() -> TestKeysets.vault(mac))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("VAULT_KEYSET is not a usable AEAD keyset")
        .hasNoCause();
  }

  @Test
  void tokenGeneratorAndVault_validKeysets_build() throws GeneralSecurityException {
    Map<String, String> env =
        Map.of(
            PiiKeys.TOKEN_KEYSET, TestKeysets.newTokenKeyset(),
            PiiKeys.VAULT_KEYSET, TestKeysets.generate(PredefinedAeadParameters.AES128_GCM));

    assertThat(PiiKeys.tokenGenerator(env).token(PiiType.PHONE, "9000000001")).startsWith("PHONE_");
    PiiVault vault = PiiKeys.vault(env);
    assertThat(vault.open(vault.seal("PHONE_AAAAAAAA", PiiType.PHONE, "9000000001")))
        .isEqualTo("9000000001");
  }
}
