package dev.invoiceintel.pii;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import org.junit.jupiter.api.Test;

class PiiVaultTest {

  @Test
  void open_sealedEntry_returnsTheValue() throws GeneralSecurityException {
    PiiVault vault = TestKeysets.vault(TestKeysets.newVaultKeyset());

    VaultEntry entry = vault.seal("PERSON_ABCDEFGH", PiiType.PERSON, "Meera Iyer");

    assertThat(entry.token()).isEqualTo("PERSON_ABCDEFGH");
    assertThat(entry.type()).isEqualTo(PiiType.PERSON);
    assertThat(vault.open(entry)).isEqualTo("Meera Iyer");
  }

  @Test
  void open_sameKeysetInANewVault_returnsTheValue() throws GeneralSecurityException {
    String keyset = TestKeysets.newVaultKeyset();
    VaultEntry entry =
        TestKeysets.vault(keyset).seal("PHONE_ABCDEFGH", PiiType.PHONE, "9000000001");

    assertThat(TestKeysets.vault(keyset).open(entry)).isEqualTo("9000000001");
  }

  @Test
  void seal_ciphertextDoesNotContainTheValue() throws GeneralSecurityException {
    VaultEntry entry =
        TestKeysets.vault(TestKeysets.newVaultKeyset())
            .seal("EMAIL_ABCDEFGH", PiiType.EMAIL, "someone@example.com");

    assertThat(new String(entry.ciphertext(), StandardCharsets.ISO_8859_1))
        .doesNotContain("someone@example.com");
  }

  @Test
  void open_entryMovedToAnotherToken_fails() throws GeneralSecurityException {
    PiiVault vault = TestKeysets.vault(TestKeysets.newVaultKeyset());
    VaultEntry entry = vault.seal("PERSON_AAAAAAAA", PiiType.PERSON, "Meera Iyer");

    VaultEntry moved = new VaultEntry("PERSON_BBBBBBBB", PiiType.PERSON, entry.ciphertext());

    assertThatThrownBy(() -> vault.open(moved))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("vault entry could not be decrypted");
  }

  @Test
  void open_tamperedCiphertext_fails() throws GeneralSecurityException {
    PiiVault vault = TestKeysets.vault(TestKeysets.newVaultKeyset());
    byte[] ciphertext = vault.seal("PAN_AAAAAAAA", PiiType.PAN, "not-a-real-pan").ciphertext();
    ciphertext[ciphertext.length - 1] ^= 1;

    assertThatThrownBy(() -> vault.open(new VaultEntry("PAN_AAAAAAAA", PiiType.PAN, ciphertext)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void open_otherKey_fails() throws GeneralSecurityException {
    VaultEntry entry =
        TestKeysets.vault(TestKeysets.newVaultKeyset())
            .seal("PIN_CODE_AAAAAAAA", PiiType.PIN_CODE, "400001");
    PiiVault other = TestKeysets.vault(TestKeysets.newVaultKeyset());

    assertThatThrownBy(() -> other.open(entry)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void seal_encryptionFailure_messageHasNoValue() {
    PiiVault vault =
        new PiiVault(
            new com.google.crypto.tink.Aead() {
              @Override
              public byte[] encrypt(byte[] plaintext, byte[] associatedData)
                  throws GeneralSecurityException {
                throw new GeneralSecurityException("boom");
              }

              @Override
              public byte[] decrypt(byte[] ciphertext, byte[] associatedData) {
                throw new UnsupportedOperationException();
              }
            });

    assertThatThrownBy(() -> vault.seal("PHONE_AAAAAAAA", PiiType.PHONE, "9000000001"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("vault encryption failed");
  }

  @Test
  void vaultEntry_copiesCiphertextAndHidesItFromToString() {
    byte[] bytes = {1, 2, 3};
    VaultEntry entry = new VaultEntry("PAN_AAAAAAAA", PiiType.PAN, bytes);
    bytes[0] = 9;
    entry.ciphertext()[1] = 9;

    assertThat(entry.ciphertext()).containsExactly(1, 2, 3);
    assertThat(entry).isEqualTo(new VaultEntry("PAN_AAAAAAAA", PiiType.PAN, new byte[] {1, 2, 3}));
    assertThat(entry)
        .hasSameHashCodeAs(new VaultEntry("PAN_AAAAAAAA", PiiType.PAN, new byte[] {1, 2, 3}));
    assertThat(entry).isNotEqualTo(new VaultEntry("PAN_AAAAAAAA", PiiType.PAN, new byte[] {1}));
    assertThat(entry)
        .isNotEqualTo(new VaultEntry("PAN_BBBBBBBB", PiiType.PAN, new byte[] {1, 2, 3}));
    assertThat(entry)
        .isNotEqualTo(new VaultEntry("PAN_AAAAAAAA", PiiType.GSTIN, new byte[] {1, 2, 3}));
    assertThat(entry.toString()).isEqualTo("VaultEntry[token=PAN_AAAAAAAA, type=PAN]");
  }
}
