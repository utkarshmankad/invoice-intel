package dev.invoiceintel.pii.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LabelsTest {

  @ParameterizedTest(name = "''{0}'' starts with ''{1}'' -> {2}")
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          Billing Address: Aarav  | Billing Address | 15
          BILL ING ADDR ESS       | Billing Address | 17
          Billingaddress          | Billing Address | 14
          • Ship To : Kavya       | Ship To         | 9
          PAN No: X               | PAN             | 3
          Pankaj Testkumar        | PAN             | -1
          Billings and more       | Billing Address | -1
          Bill                    | Billing Address | -1
          Shipping Address        | Ship To         | -1
          State/UT Code: 29       | State/UT Code   | 13
          """)
  void prefixEnd_matchesLabelsAtWordBoundary(String text, String label, int expected) {
    assertThat(Labels.prefixEnd(text, label)).isEqualTo(expected);
  }
}
