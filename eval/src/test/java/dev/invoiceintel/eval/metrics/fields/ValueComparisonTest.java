package dev.invoiceintel.eval.metrics.fields;

import static org.assertj.core.api.Assertions.assertThat;

import dev.invoiceintel.eval.metrics.fields.HeaderField.Kind;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ValueComparisonTest {

  @ParameterizedTest(name = "{0}: [{1}] vs [{2}] -> {3}")
  @CsvSource(
      nullValues = "NULL",
      value = {
        // null semantics (architecture §9.2)
        "TEXT,   NULL,                    NULL,                   true",
        "TEXT,   NULL,                    Example Store,          false",
        "TEXT,   Example Store,           NULL,                   false",
        "TEXT,   '   ',                   NULL,                   true",
        // text: trim, collapse whitespace, ignore case
        "TEXT,   Example  Books   Store,  ' example books store ', true",
        "TEXT,   Karnataka,               Kerala,                 false",
        "TEXT,   TAX_INVOICE,             tax_invoice,            true",
        "TEXT,   true,                    TRUE,                   true",
        // tokens: exact after trim
        "TOKEN,  INV_7f3a,                ' INV_7f3a ',           true",
        "TOKEN,  INV_7f3a,                inv_7f3a,               false",
        "TOKEN,  INV_7f3a,                NULL,                   false",
        // dates: ISO, else text fallback
        "DATE,   2024-03-15,              2024-03-15,             true",
        "DATE,   2024-03-15,              2024-03-16,             false",
        "DATE,   15/03/2024,              15/03/2024,             true",
        "DATE,   2024-03-15,              15/03/2024,             false",
        // amounts: by value, scale ignored, else text fallback
        "AMOUNT, 1227.20,                 1227.2,                 true",
        "AMOUNT, 1227.20,                 1227.21,                false",
        "AMOUNT, 299.00,                  299,                    true",
        "AMOUNT, n/a,                     N/A,                    true",
        "AMOUNT, 299.00,                  n/a,                    false",
      })
  void matches_table(Kind kind, String expected, String actual, boolean match) {
    assertThat(ValueComparison.matches(kind, expected, actual)).isEqualTo(match);
  }
}
