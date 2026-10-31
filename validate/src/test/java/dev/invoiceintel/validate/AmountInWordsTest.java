package dev.invoiceintel.validate;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class AmountInWordsTest {

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource(
      delimiter = '|',
      value = {
        // units, teens, tens
        "Zero | 0.00",
        "One | 1.00",
        "Nineteen | 19.00",
        "Forty-Five | 45.00",
        "Ninety Nine | 99.00",
        // hundreds and thousands
        "One Hundred | 100.00",
        "One Hundred and Five | 105.00",
        "Four Hundred Ninety-Nine | 499.00",
        "Twelve Hundred | 1200.00",
        "One Thousand One Hundred Eighty | 1180.00",
        "Ninety Nine Thousand Nine Hundred Ninety Nine | 99999.00",
        // lakh
        "One Lakh | 100000.00",
        "One Lakh Twenty Thousand Five Hundred | 120500.00",
        "Twelve Lakhs Thirty Four Thousand Five Hundred Sixty Seven | 1234567.00",
        "Two Lac Fifty Thousand | 250000.00",
        "Five Lacs | 500000.00",
        "Ninety Nine Lakh Ninety Nine Thousand Nine Hundred Ninety Nine | 9999999.00",
        // crore
        "One Crore | 10000000.00",
        "Two Crores | 20000000.00",
        "One Crore Two Lakh Three Thousand Four Hundred Five | 10203405.00",
        "Twelve Crore Thirty Four Lakh Fifty Six Thousand Seven Hundred Eighty Nine"
            + " | 123456789.00",
        "Five Hundred Crore | 5000000000.00",
        "One Lakh Crore | 1000000000000.00",
        "Twenty Five Thousand Crore Ten | 250000000010.00",
        // paise
        "One Thousand One Hundred Eighty Rupees and Fifty Paise Only | 1180.50",
        "Rupees One Thousand One Hundred Eighty and Fifty Paise Only | 1180.50",
        "One Thousand Rupees Five Paise | 1000.05",
        "INR Ten and Ninety Nine Paise | 10.99",
        "One Hundred and Five Rupees and Forty Paise | 105.40",
        "Fifty Paise Only | 0.50",
        "Zero Rupees and One Paisa | 0.01",
        "One Lakh Rupees and Zero Paise | 100000.00",
        // currency words, punctuation and case
        "Rupees One Thousand Only | 1000.00",
        "Rs. Four Hundred Ninety-Nine Only. | 499.00",
        "₹ Five Hundred | 500.00",
        "one thousand one hundred eighty only | 1180.00",
        "ONE THOUSAND, ONE HUNDRED AND EIGHTY RUPEES ONLY | 1180.00",
        "Rupee One | 1.00",
        // sign
        "Minus One Thousand One Hundred Eighty Only | -1180.00",
        "Negative Rupees Fifty and Twenty Five Paise | -50.25"
      })
  void parse_validWords_returnsAmount(String words, String expected) {
    assertThat(AmountInWords.parse(words)).contains(new BigDecimal(expected));
  }

  @ParameterizedTest(name = "\"{0}\" is not an amount")
  @ValueSource(
      strings = {
        "",
        "Only",
        "Rupees Only",
        "1180.00",
        "One Thousand 180",
        "Eleven Hundred Eighty Dollars",
        "Twenty Thirty",
        "Five Six",
        "Twelve Five",
        "Fifteen Twenty",
        "Twenty Eleven",
        "Hundred",
        "Twenty Hundred",
        "One Hundred Five Hundred",
        "Thousand",
        "One Thousand Two Thousand",
        "One Thousand Two Lakh",
        "One Lakh One Lakh",
        "Crore",
        "Two Crore Three Crore",
        "Zero Zero",
        "One Zero",
        "Zero Thousand",
        "One Hundred Paise",
        "Fifty Paise Ten",
        "Paise",
        "One Rupees Two",
        "Ten Rupees and Two Rupees Five Paise",
        "Minus",
        "Minus Minus Five"
      })
  void parse_invalidWords_isEmpty(String words) {
    assertThat(AmountInWords.parse(words)).isEmpty();
  }

  @Test
  void parse_null_isEmpty() {
    assertThat(AmountInWords.parse(null)).isEmpty();
  }
}
