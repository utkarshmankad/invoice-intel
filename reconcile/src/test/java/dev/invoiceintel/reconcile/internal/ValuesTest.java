package dev.invoiceintel.reconcile.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class ValuesTest {

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "  ", "Not Available", "not available", "N/A", "-"})
  void text_blankOrPlaceholder_isAbsent(String cell) {
    assertThat(Values.text(cell)).isEmpty();
    assertThat(Values.date(cell)).isEmpty();
    assertThat(Values.quantity(cell)).isEmpty();
    assertThat(Values.amount(cell)).isEmpty();
  }

  @ParameterizedTest
  @CsvSource({
    "2024-03-05, 2024-03-05",
    "2024-03-05T10:15:30, 2024-03-05",
    "2024-03-05T10:15:30Z, 2024-03-05",
    "2024-03-05T20:00:00.000Z, 2024-03-06", // after 18:30 UTC it is the next day in IST
    "2024-03-05T23:00:00+05:30, 2024-03-05"
  })
  void date_isoForms_giveTheIstDate(String cell, LocalDate expected) {
    assertThat(Values.date(cell)).contains(expected);
  }

  @ParameterizedTest
  @ValueSource(strings = {"05/03/2024", "yesterday", "2024-13-01"})
  void date_otherForms_areRejected(String cell) {
    assertThatIllegalArgumentException().isThrownBy(() -> Values.date(cell));
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "1299|1299.00",
        "1,299.5|1299.50",
        "₹ 1,299.00|1299.00",
        "INR 12.345|12.35",
        "Rs. 99|99.00",
        "-50.00|-50.00"
      })
  void amount_rupeeForms_areReadAtScaleTwo(String cell, BigDecimal expected) {
    assertThat(Values.amount(cell))
        .hasValueSatisfying(
            a -> {
              assertThat(a).isEqualByComparingTo(expected);
              assertThat(a.scale()).isEqualTo(2);
            });
  }

  @ParameterizedTest
  @ValueSource(strings = {"12.3.4", "abc", "1e5", "$5"})
  void amount_nonAmounts_areRejected(String cell) {
    assertThatIllegalArgumentException().isThrownBy(() -> Values.amount(cell));
  }

  @ParameterizedTest
  @CsvSource({"1, 1", " 12 , 12"})
  void quantity_wholeNumbers_areRead(String cell, int expected) {
    assertThat(Values.quantity(cell)).contains(expected);
  }

  @ParameterizedTest
  @ValueSource(strings = {"1.5", "two"})
  void quantity_others_areRejected(String cell) {
    assertThatIllegalArgumentException().isThrownBy(() -> Values.quantity(cell));
  }
}
