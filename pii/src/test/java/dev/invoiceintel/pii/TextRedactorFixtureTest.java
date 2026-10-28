package dev.invoiceintel.pii;

import static org.assertj.core.api.Assertions.assertThat;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.common.fixtures.InvoiceType;
import dev.invoiceintel.common.fixtures.LineItem;
import dev.invoiceintel.common.fixtures.Seller;
import dev.invoiceintel.common.fixtures.SyntheticInvoice;
import dev.invoiceintel.common.fixtures.SyntheticInvoiceGenerator;
import dev.invoiceintel.text.PdfTextExtractor;
import dev.invoiceintel.text.TextLayer;
import dev.invoiceintel.text.Word;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * M2.6 done-when: detectors → {@link SpanMerger} → {@link TextRedactor} on synthetic invoices
 * leaves tokens and none of the fixture's buyer PII, and keeps what isn't PII (specs.md §6.1).
 */
class TextRedactorFixtureTest {

  private static final Pattern WHITESPACE = Pattern.compile("\\s+");
  private static final Pattern TOKEN = Pattern.compile("\\b[A-Z_]+_[A-Z2-7]{8}\\b");

  private static TokenGenerator tokens;
  private static PiiVault vault;

  private final List<PiiDetector> detectors =
      List.of(new RegexDetector(), new ChecksumDetector(), new AddressBlockDetector());

  @BeforeAll
  static void keys() throws GeneralSecurityException {
    tokens = TestKeysets.tokens(TestKeysets.newTokenKeyset());
    vault = TestKeysets.vault(TestKeysets.newVaultKeyset());
  }

  static Stream<Arguments> cases() {
    return Arrays.stream(InvoiceType.values())
        .flatMap(
            type ->
                Stream.of(1L, 7L, 42L, 1234L, 99_999L)
                    .flatMap(
                        seed ->
                            Arrays.stream(RedactionMode.values())
                                .map(mode -> Arguments.of(type, seed, mode))));
  }

  private TextRedactor.Result redact(SyntheticInvoice invoice, RedactionMode mode) {
    TextLayer layer = new PdfTextExtractor().extract(SyntheticInvoiceGenerator.toPdf(invoice));
    DocumentContext ctx = DocumentContext.of(DocumentId.random(), layer);
    var spans = new ArrayList<PiiSpan>();
    detectors.forEach(detector -> spans.addAll(detector.detect(layer, ctx)));
    List<PiiSpan> merged = new SpanMerger().merge(layer, spans);
    return new TextRedactor(tokens, vault).redact(layer, merged, ctx, mode);
  }

  @ParameterizedTest(name = "{0} seed {1} {2}")
  @MethodSource("cases")
  void redact_syntheticInvoice_containsTokensAndNoBuyerPii(
      InvoiceType type, long seed, RedactionMode mode) {
    SyntheticInvoice invoice = SyntheticInvoiceGenerator.generate(type, seed);

    TextRedactor.Result result = redact(invoice, mode);
    String text = result.content().text();

    assertThat(TOKEN.matcher(text).results().count()).as("tokens").isPositive();
    assertThat(text).contains(tokens.token(PiiType.ORDER_NUMBER, invoice.orderNumber()));
    assertThat(text).contains(tokens.token(PiiType.INVOICE_NUMBER, invoice.invoiceNumber()));
    if (mode == RedactionMode.PSEUDONYMIZE) {
      assertThat(text)
          .contains(tokens.token(PiiType.PERSON, invoice.buyer().name()))
          .contains(tokens.token(PiiType.PHONE, invoice.buyer().phone()))
          .contains(tokens.token(PiiType.EMAIL, invoice.buyer().email()));
    } else {
      assertThat(text).contains("[REDACTED:PERSON]", "[REDACTED:PHONE]", "[REDACTED:EMAIL]");
    }
    assertThat(text).contains("[REDACTED:PIN_CODE]");

    String upper = text.toUpperCase(Locale.ROOT);
    String compact = WHITESPACE.matcher(upper).replaceAll("");
    List<String> wordDigits =
        result.content().pages().stream()
            .flatMap(page -> page.words().stream())
            .map(Word::text)
            .map(TextRedactorFixtureTest::digits)
            .toList();
    for (String value : invoice.piiValues()) {
      String normalized = TokenGenerator.normalize(value);
      assertThat(upper).as("PII value present").doesNotContain(normalized);
      assertThat(compact)
          .as("PII value present without spaces")
          .doesNotContain(WHITESPACE.matcher(normalized).replaceAll(""));
      String valueDigits = digits(value);
      if (valueDigits.length() >= 6) {
        assertThat(wordDigits)
            .as("PII digits present in a word")
            .noneMatch(
                word ->
                    word.contains(valueDigits)
                        || valueDigits.length() > 10
                            && word.contains(valueDigits.substring(valueDigits.length() - 10)));
      }
    }
    // The national ten digits of the mobile number, without +91.
    String mobile = digits(invoice.buyer().phone());
    String national = mobile.substring(mobile.length() - 10);
    assertThat(wordDigits).noneMatch(word -> word.contains(national));
  }

  @ParameterizedTest(name = "{0} seed {1} {2}")
  @MethodSource("cases")
  void redact_syntheticInvoice_keepsSellerStateAndInvoiceData(
      InvoiceType type, long seed, RedactionMode mode) {
    SyntheticInvoice invoice = SyntheticInvoiceGenerator.generate(type, seed);
    Seller seller = invoice.seller();

    String text = redact(invoice, mode).content().text();

    assertThat(text)
        .contains(seller.name(), seller.street(), seller.pinCode(), seller.pan(), seller.taxId())
        .contains(seller.state().city() + ", " + seller.state().displayName())
        // Buyer state is never PII (specs.md §6.1); "Place of Supply" repeats it.
        .contains("Place of Supply: " + invoice.buyer().state().displayName())
        .contains(invoice.orderDate().toString(), invoice.invoiceDate().toString())
        .contains(invoice.grandTotal().toPlainString(), invoice.taxTotal().toPlainString());
    for (LineItem item : invoice.lineItems()) {
      assertThat(text).contains(item.hsnCode(), item.netAmount().toPlainString());
    }
  }

  @ParameterizedTest(name = "{0} seed {1} {2}")
  @MethodSource("cases")
  void redact_syntheticInvoice_vaultOpensToBuyerValuesOnlyWhenPseudonymizing(
      InvoiceType type, long seed, RedactionMode mode) {
    SyntheticInvoice invoice = SyntheticInvoiceGenerator.generate(type, seed);

    TextRedactor.Result result = redact(invoice, mode);

    if (mode == RedactionMode.REDACT) {
      assertThat(result.vaultEntries()).isEmpty();
      return;
    }
    List<String> opened = result.vaultEntries().stream().map(vault::open).toList();
    assertThat(opened)
        .contains(
            invoice.buyer().name(),
            invoice.buyer().phone(),
            invoice.buyer().email(),
            invoice.orderNumber(),
            invoice.invoiceNumber())
        .doesNotContain(invoice.buyer().pinCode(), invoice.seller().name());
    assertThat(result.vaultEntries()).noneMatch(entry -> entry.type() == PiiType.PIN_CODE);
  }

  private static String digits(String value) {
    StringBuilder out = new StringBuilder();
    for (char c : value.toCharArray()) {
      if (c >= '0' && c <= '9') {
        out.append(c);
      }
    }
    return out.toString();
  }
}
