package dev.invoiceintel.common.checksum;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Known answers plus seeded property tests: generated numbers with their check digit pass, and
 * every single-digit substitution and adjacent transposition of them fails.
 */
class VerhoeffTest {

  private static final int CASES = 500;

  @ParameterizedTest
  @CsvSource({"236, 3", "12345, 1", "142857, 0", "75872, 2", "0, 4"})
  void checkDigit_publishedExamples_matchReference(String digits, char expected) {
    assertThat(Verhoeff.checkDigit(digits)).isEqualTo(expected);
    assertThat(Verhoeff.isValid(digits + expected)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"2364", "123450", "1428571", "758723", "", "12a4", "-236", "236 3"})
  void isValid_wrongCheckDigitOrNonDigits_isFalse(String value) {
    assertThat(Verhoeff.isValid(value)).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "12x", "٢٣٦"})
  void checkDigit_emptyOrNonAsciiDigits_isRejected(String digits) {
    assertThatIllegalArgumentException().isThrownBy(() -> Verhoeff.checkDigit(digits));
  }

  static Stream<String> generated() {
    var random = new Random(20261028L);
    return IntStream.range(0, CASES)
        .mapToObj(
            i -> {
              var body = new StringBuilder();
              int length = 1 + random.nextInt(20);
              for (int j = 0; j < length; j++) {
                body.append((char) ('0' + random.nextInt(10)));
              }
              return body.toString() + Verhoeff.checkDigit(body);
            });
  }

  @ParameterizedTest
  @MethodSource("generated")
  void isValid_generatedWithCheckDigit_isTrue(String value) {
    assertThat(Verhoeff.isValid(value)).isTrue();
  }

  @ParameterizedTest
  @MethodSource("generated")
  void isValid_anySingleDigitSubstitution_isFalse(String value) {
    for (int i = 0; i < value.length(); i++) {
      for (char d = '0'; d <= '9'; d++) {
        if (d != value.charAt(i)) {
          String changed = value.substring(0, i) + d + value.substring(i + 1);
          assertThat(Verhoeff.isValid(changed)).as("digit %d changed", i).isFalse();
        }
      }
    }
  }

  @ParameterizedTest
  @MethodSource("generated")
  void isValid_anyAdjacentTransposition_isFalse(String value) {
    for (int i = 0; i + 1 < value.length(); i++) {
      if (value.charAt(i) != value.charAt(i + 1)) {
        String swapped =
            value.substring(0, i) + value.charAt(i + 1) + value.charAt(i) + value.substring(i + 2);
        assertThat(Verhoeff.isValid(swapped)).as("digits %d and %d swapped", i, i + 1).isFalse();
      }
    }
  }
}
