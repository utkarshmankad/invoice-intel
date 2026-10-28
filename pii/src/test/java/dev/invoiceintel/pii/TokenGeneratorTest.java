package dev.invoiceintel.pii;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TokenGeneratorTest {

  /** Fixed test key: bytes 0..31. Not a real key. */
  private static byte[] testKey() {
    byte[] key = new byte[32];
    for (int i = 0; i < key.length; i++) {
      key[i] = (byte) i;
    }
    return key;
  }

  @Test
  void token_matchesTheArchitectureFormula() throws Exception {
    TokenGenerator tokens = TestKeysets.tokens(TestKeysets.tokenKeyset(testKey()));

    javax.crypto.Mac hmac = javax.crypto.Mac.getInstance("HmacSHA256");
    hmac.init(new SecretKeySpec(testKey(), "HmacSHA256"));
    byte[] tag = hmac.doFinal("RAVI KUMAR".getBytes(StandardCharsets.UTF_8));
    String expected = rfc4648Base32(tag).substring(0, 8);

    assertThat(tokens.token(PiiType.PERSON, "Ravi Kumar")).isEqualTo("PERSON_" + expected);
  }

  @Test
  void token_sameValueAndKey_sameTokenAcrossGenerators() throws Exception {
    String keyset = TestKeysets.newTokenKeyset();

    String first = TestKeysets.tokens(keyset).token(PiiType.ORDER_NUMBER, "404-0000000-0000001");
    String second = TestKeysets.tokens(keyset).token(PiiType.ORDER_NUMBER, "404-0000000-0000001");

    assertThat(first).isEqualTo(second).matches("ORDER_NUMBER_[A-Z2-7]{8}");
  }

  @Test
  void token_differentValues_differentTokens() throws Exception {
    TokenGenerator tokens = TestKeysets.tokens(TestKeysets.newTokenKeyset());

    assertThat(tokens.token(PiiType.PHONE, "9000000001"))
        .isNotEqualTo(tokens.token(PiiType.PHONE, "9000000002"));
  }

  @Test
  void token_differentKeys_differentTokens() throws Exception {
    String value = "Asha Verma";

    assertThat(TestKeysets.tokens(TestKeysets.newTokenKeyset()).token(PiiType.PERSON, value))
        .isNotEqualTo(
            TestKeysets.tokens(TestKeysets.newTokenKeyset()).token(PiiType.PERSON, value));
  }

  @Test
  void token_spacingAndCaseVariants_sameToken() throws Exception {
    TokenGenerator tokens = TestKeysets.tokens(TestKeysets.newTokenKeyset());

    assertThat(tokens.token(PiiType.PERSON, "  asha\n verma "))
        .isEqualTo(tokens.token(PiiType.PERSON, "Asha Verma"));
  }

  @Test
  void token_typeIsThePrefix() throws Exception {
    TokenGenerator tokens = TestKeysets.tokens(TestKeysets.newTokenKeyset());

    String asPin = tokens.token(PiiType.PIN_CODE, "400001");
    String asPhone = tokens.token(PiiType.PHONE, "400001");

    assertThat(asPin).startsWith("PIN_CODE_");
    assertThat(asPhone).startsWith("PHONE_");
    assertThat(asPin.substring("PIN_CODE_".length()))
        .isEqualTo(asPhone.substring("PHONE_".length()));
  }

  @Test
  void token_panAndAadhaar_useIdPrefixPerSpec() throws Exception {
    TokenGenerator tokens = TestKeysets.tokens(TestKeysets.newTokenKeyset());

    assertThat(tokens.token(PiiType.PAN, "x1")).startsWith("ID_");
    assertThat(tokens.token(PiiType.AADHAAR, "x1")).startsWith("ID_");
    assertThat(tokens.token(PiiType.GSTIN, "x1")).startsWith("GSTIN_");
    assertThat(TokenGenerator.prefix(PiiType.ORDER_NUMBER)).isEqualTo("ORDER_NUMBER");
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "abc|ABC",
        "'  a   b  '|A B",
        "'a\tb\nc'|A B C",
        "az09-./@|AZ09-./@",
        "é|é" // only ASCII letters are case-folded
      })
  void normalize_foldsCaseSpacingAndWidth(String input, String expected) {
    assertThat(TokenGenerator.normalize(input)).isEqualTo(expected);
  }

  @Test
  void base32Prefix_usesRfc4648AlphabetOnFirstFiveBytes() {
    assertThat(TokenGenerator.base32Prefix(new byte[] {0, 0, 0, 0, 0, 1})).isEqualTo("AAAAAAAA");
    assertThat(TokenGenerator.base32Prefix(new byte[] {-1, -1, -1, -1, -1})).isEqualTo("77777777");
    // "fooba" is the RFC 4648 §10 test vector for "MZXW6YTB".
    assertThat(TokenGenerator.base32Prefix("fooba".getBytes(StandardCharsets.US_ASCII)))
        .isEqualTo("MZXW6YTB");
  }

  @Test
  void token_nullArguments_rejected() throws GeneralSecurityException {
    TokenGenerator tokens = TestKeysets.tokens(TestKeysets.newTokenKeyset());

    assertThatThrownBy(() -> tokens.token(null, "x")).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> tokens.token(PiiType.PHONE, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void token_macFailure_messageHasNoValue() {
    TokenGenerator tokens =
        new TokenGenerator(
            new com.google.crypto.tink.Mac() {
              @Override
              public byte[] computeMac(byte[] data) throws GeneralSecurityException {
                throw new GeneralSecurityException("boom");
              }

              @Override
              public void verifyMac(byte[] mac, byte[] data) {
                throw new UnsupportedOperationException();
              }
            });

    assertThatThrownBy(() -> tokens.token(PiiType.EMAIL, "someone@example.com"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("token MAC failed");
  }

  /** Independent RFC 4648 base32 (no padding) via a bit string, as the test oracle. */
  private static String rfc4648Base32(byte[] bytes) {
    String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    StringBuilder bits = new StringBuilder();
    for (byte b : bytes) {
      bits.append(String.format("%8s", Integer.toBinaryString(b & 0xFF)).replace(' ', '0'));
    }
    StringBuilder out = new StringBuilder();
    for (int i = 0; i + 5 <= bits.length(); i += 5) {
      out.append(alphabet.charAt(Integer.parseInt(bits.substring(i, i + 5), 2)));
    }
    return out.toString();
  }
}
