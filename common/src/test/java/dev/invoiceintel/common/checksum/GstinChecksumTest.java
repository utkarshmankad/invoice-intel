package dev.invoiceintel.common.checksum;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Known answers plus seeded property tests: generated GSTINs pass, and every single-character
 * substitution fails (the weights 1 and 2 with folding make each position's value injective mod
 * 36).
 */
class GstinChecksumTest {

  private static final String BASE36 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
  private static final String LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
  private static final int CASES = 300;

  @ParameterizedTest
  @CsvSource({
    // Widely published GSTIN format example; its check character is V.
    "27AAPFU0939F1Z, V",
    "29ABCDE1234F1Z, W",
    "07ZZZZZ9999Z9Z, 2"
  })
  void checkChar_knownBodies_matchesReference(String body, char expected) {
    assertThat(GstinChecksum.checkChar(body)).isEqualTo(expected);
    assertThat(GstinChecksum.isValid(body + expected)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"27AAPFU0939F1", "27aapfu0939f1z", "27AAPFU0939F1-"})
  void checkChar_malformedBody_isRejected(String body) {
    assertThatIllegalArgumentException().isThrownBy(() -> GstinChecksum.checkChar(body));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "27AAPFU0939F1ZW", // wrong check character
        "27aapfu0939f1zv", // lower case
        "27AAPFU0939F1Z", // too short
        "27AAPFU0939F1ZVX", // too long
        "27AAPFU0939F1Y" + "V", // 14th character not Z
        ""
      })
  void isValid_malformedOrWrongCheck_isFalse(String value) {
    assertThat(GstinChecksum.isValid(value)).isFalse();
  }

  @Test
  void isValid_rightCheckCharButWrongLayout_isFalseWhileCheckCharPasses() {
    String body = "ABCDEFGHIJKLMN";
    String value = body + GstinChecksum.checkChar(body);

    assertThat(GstinChecksum.hasValidCheckChar(value)).isTrue();
    assertThat(GstinChecksum.isValid(value)).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "27AAPFU0939F1Z", "27aapfu0939f1zv", "27AAPFU0939F1-V"})
  void hasValidCheckChar_wrongLengthOrCharacters_isFalse(String value) {
    assertThat(GstinChecksum.hasValidCheckChar(value)).isFalse();
  }

  static Stream<String> generated() {
    var random = new Random(20261028L);
    return IntStream.range(0, CASES)
        .mapToObj(
            i -> {
              String body =
                  String.format("%02d", 1 + random.nextInt(38))
                      + chars(random, LETTERS, 5)
                      + String.format("%04d", random.nextInt(10_000))
                      + chars(random, LETTERS, 1)
                      + chars(random, BASE36.substring(1), 1)
                      + 'Z';
              return body + GstinChecksum.checkChar(body);
            });
  }

  @ParameterizedTest
  @MethodSource("generated")
  void isValid_generatedGstin_isTrue(String gstin) {
    assertThat(GstinChecksum.isValid(gstin)).isTrue();
    assertThat(GstinChecksum.hasValidCheckChar(gstin)).isTrue();
  }

  @ParameterizedTest
  @MethodSource("generated")
  void hasValidCheckChar_anySingleCharacterSubstitution_isFalse(String gstin) {
    for (int i = 0; i < gstin.length(); i++) {
      for (int v = 0; v < BASE36.length(); v++) {
        char c = BASE36.charAt(v);
        if (c != gstin.charAt(i)) {
          String changed = gstin.substring(0, i) + c + gstin.substring(i + 1);
          assertThat(GstinChecksum.hasValidCheckChar(changed))
              .as("character %d changed", i)
              .isFalse();
          assertThat(GstinChecksum.isValid(changed)).as("character %d changed", i).isFalse();
        }
      }
    }
  }

  private static String chars(Random random, String alphabet, int count) {
    var out = new StringBuilder(count);
    for (int i = 0; i < count; i++) {
      out.append(alphabet.charAt(random.nextInt(alphabet.length())));
    }
    return out.toString();
  }
}
