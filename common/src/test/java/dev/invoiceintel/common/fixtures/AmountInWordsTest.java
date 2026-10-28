package dev.invoiceintel.common.fixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class AmountInWordsTest {

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "0.00        | Zero Rupees Only",
        "0.50        | Zero Rupees and Fifty Paise Only",
        "7           | Seven Rupees Only",
        "19.05       | Nineteen Rupees and Five Paise Only",
        "40          | Forty Rupees Only",
        "99.99       | Ninety Nine Rupees and Ninety Nine Paise Only",
        "100         | One Hundred Rupees Only",
        "2360.50     | Two Thousand Three Hundred Sixty Rupees and Fifty Paise Only",
        "100000      | One Lakh Rupees Only",
        "123456.75   | One Lakh Twenty Three Thousand Four Hundred Fifty Six Rupees and"
            + " Seventy Five Paise Only",
        "10000001    | One Crore One Rupees Only",
        "1234567890  | One Hundred Twenty Three Crore Forty Five Lakh Sixty Seven Thousand"
            + " Eight Hundred Ninety Rupees Only",
        "12.345      | Twelve Rupees and Thirty Five Paise Only"
      })
  void of_amount_writesIndianNumbering(String amount, String expected) {
    assertThat(AmountInWords.of(new BigDecimal(amount))).isEqualTo(expected);
  }

  @ParameterizedTest
  @ValueSource(strings = {"-1", "100000000000"})
  void of_outOfRange_isRejected(String amount) {
    assertThatIllegalArgumentException().isThrownBy(() -> AmountInWords.of(new BigDecimal(amount)));
  }
}
