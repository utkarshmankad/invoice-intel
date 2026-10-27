package dev.invoiceintel.app.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PiiLogMaskerTest {

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "contact test.buyer@example.com now | contact [EMAIL] now",
        "a.b+tag@mail.example.co.in | [EMAIL]",
        "call 9876543210 | call [PHONE]",
        "call +91 9876543210 | call [PHONE]",
        "call +91-98765-43210 | call [PHONE]",
        "call 919876543210 | call [PHONE]",
        "call 09876543210 | call [PHONE]",
        "call 98765 43210. | call [PHONE].",
        "x 6123456789,7123456789 | x [PHONE],[PHONE]",
        "user42@example.org or 8123456789 | [EMAIL] or [PHONE]",
      })
  void mask_piiShapedValues_areReplaced(String input, String expected) {
    assertThat(PiiLogMasker.mask(input)).isEqualTo(expected);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "document 00000000-0000-4000-8000-000000000001 reached PII_CLEAN",
        "stage TEXT run 2 took 1234 ms",
        "5876543210", // 10 digits, but no Indian mobile starts with 5
        "98765432101", // 11 digits without a valid prefix
        "123456", // PIN codes are covered by the M2.1 detectors, not this stub
        "not-an-email@localhost",
      })
  void mask_nonPiiValues_areUnchanged(String input) {
    assertThat(PiiLogMasker.mask(input)).isEqualTo(input);
  }

  @ParameterizedTest
  @NullAndEmptySource
  void mask_nullOrEmpty_isReturnedAsIs(String input) {
    assertThat(PiiLogMasker.mask(input)).isEqualTo(input);
  }
}
