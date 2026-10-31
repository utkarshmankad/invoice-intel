package dev.invoiceintel.validate;

import static dev.invoiceintel.validate.ArithmeticFixtures.CONTEXT;
import static dev.invoiceintel.validate.ArithmeticFixtures.money;
import static org.assertj.core.api.Assertions.assertThat;

import dev.invoiceintel.common.extraction.Field;
import dev.invoiceintel.common.extraction.InvoiceExtraction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class AmountInWordsRuleTest {

  private final AmountInWordsRule rule = new AmountInWordsRule();

  @Test
  void id_isVal7() {
    assertThat(rule.id()).isEqualTo("VAL-7");
  }

  @Test
  void check_consistentFixtureWithoutWords_passes() {
    assertThat(rule.check(ExtractionFixtures.consistentTaxInvoice(), CONTEXT))
        .isEqualTo(RuleResult.pass());
  }

  @ParameterizedTest(name = "\"{0}\" vs {1} -> {2}")
  @CsvSource(
      delimiter = '|',
      value = {
        "One Thousand One Hundred Eighty Only | 1180.00 | PASS",
        "One Thousand One Hundred Eighty Rupees and Fifty Paise | 1180.50 | PASS",
        "One Thousand One Hundred Eighty Only | 1180.60 | PASS", // paise left out
        "One Thousand One Hundred Eighty One | 1180.00 | PASS", // exactly +1.00
        "One Thousand One Hundred Seventy Nine | 1180.00 | PASS", // exactly -1.00
        "One Thousand One Hundred Eighty One Rupees One Paisa | 1180.00 | FAIL",
        "One Thousand One Hundred Seventy Eight Rupees Ninety Nine Paise | 1180.00 | FAIL",
        "Eleven Thousand Eight Hundred | 1180.00 | FAIL",
        "One Lakh Eighteen Thousand | 118000.00 | PASS",
        "One Lakh Eighteen Thousand | 1180.00 | FAIL",
        "Two Crore Fifty Lakh | 25000000.00 | PASS",
        "One Thousand One Hundred Eighty | -1180.00 | PASS", // unsigned words on a credit note
        "Minus One Thousand One Hundred Eighty | -1180.00 | PASS",
        "Minus One Thousand One Hundred Eighty | 1180.00 | FAIL"
      })
  void check_comparesWordsWithGrandTotal(String words, String grandTotal, RuleOutcome expected) {
    assertThat(rule.check(with(words, grandTotal), CONTEXT).outcome()).isEqualTo(expected);
  }

  @Test
  void check_mismatch_failsNamingAmountsOnly() {
    assertThat(rule.check(with("One Thousand Two Hundred Only", "1180.00"), CONTEXT))
        .isEqualTo(
            RuleResult.fail(
                "amountInWords states 1200.00, differs from grandTotal 1180.00 by more than 1.00"));
  }

  @ParameterizedTest(name = "\"{0}\" -> PASS")
  @ValueSource(strings = {"", "   "})
  void check_blankWords_passes(String words) {
    assertThat(rule.check(with(words, "1180.00"), CONTEXT)).isEqualTo(RuleResult.pass());
  }

  @Test
  void check_noWordsField_passes() {
    assertThat(rule.check(withField(null, "1180.00"), CONTEXT)).isEqualTo(RuleResult.pass());
  }

  @ParameterizedTest(name = "\"{0}\" -> WARN")
  @ValueSource(strings = {"1180.00", "WORDS_TOKEN_01", "Eleven Eighty Dollars"})
  void check_unreadableWords_warnsWithoutQuotingThem(String words) {
    assertThat(rule.check(with(words, "1180.00"), CONTEXT))
        .isEqualTo(RuleResult.warn("amountInWords could not be read as an amount"));
  }

  @Test
  void check_missingGrandTotal_warns() {
    assertThat(rule.check(with("One Thousand", null), CONTEXT))
        .isEqualTo(RuleResult.warn("grandTotal missing; cannot check amountInWords"));
  }

  private static InvoiceExtraction with(String words, String grandTotal) {
    return withField(Field.llm(words, 0.9), grandTotal);
  }

  private static InvoiceExtraction withField(Field<String> words, String grandTotal) {
    InvoiceExtraction x = ExtractionFixtures.consistentTaxInvoice();
    return new InvoiceExtraction(
        x.documentClass(),
        x.invoiceNumber(),
        x.invoiceDate(),
        x.orderNumber(),
        x.orderDate(),
        x.sellerName(),
        x.sellerGstin(),
        x.sellerPan(),
        x.sellerAddress(),
        x.placeOfSupply(),
        x.placeOfDelivery(),
        x.buyerState(),
        x.reverseCharge(),
        x.currency(),
        x.subtotal(),
        x.totalTax(),
        money(grandTotal),
        words,
        x.originalInvoiceRef(),
        x.lineItems());
  }
}
