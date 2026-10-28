package dev.invoiceintel.text;

import static org.assertj.core.api.Assertions.assertThat;

import dev.invoiceintel.common.DocumentClass;
import dev.invoiceintel.common.fixtures.InvoiceType;
import dev.invoiceintel.common.fixtures.SyntheticInvoiceGenerator;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/** Synthetic fixtures and made-up text only (testing.md). */
class DocumentClassifierTest {

  private final DocumentClassifier classifier = new DocumentClassifier();

  static Stream<Arguments> fixtures() {
    return Arrays.stream(InvoiceType.values())
        .flatMap(type -> Stream.of(1L, 7L, 42L, 2024L).map(seed -> Arguments.of(type, seed)));
  }

  @ParameterizedTest(name = "{0} seed {1}")
  @MethodSource("fixtures")
  void classify_syntheticFixture_matchesItsType(InvoiceType type, long seed) {
    byte[] pdf = SyntheticInvoiceGenerator.toPdf(SyntheticInvoiceGenerator.generate(type, seed));

    DocumentClass result = classifier.classify(new PdfTextExtractor().extract(pdf));

    assertThat(result).isEqualTo(expected(type));
  }

  @ParameterizedTest(name = "{1}: {0}")
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          Tax Invoice Invoice Date: 12.03.2021 CGST 9% SGST 9%                  | TAX_INVOICE
          TAXINVOICE GSTIN 29ABCDE1234F1Z5 IGST                                  | TAX_INVOICE
          Invoice Invoice Date: 05 Aug 2019 Seller GSTIN 27ABCDE1234F1Z5 IGST 18% | TAX_INVOICE
          Credit Note Original Invoice Number INV-1 Invoice Date: 2020-02-02      | CREDIT_NOTE
          CREDIT NOTE dated 2015-04-01 VAT 5%                                     | CREDIT_NOTE
          Bill of Supply Invoice Date: 2022-06-30 GSTIN 07ABCDE1234F1Z5           | BILL_OF_SUPPLY
          Retail Invoice Invoice Date: 15/03/2016 VAT 5%                          | PRE_GST_INVOICE
          Invoice Invoice Date: 30-Jun-2017 TIN 29123456789 CST 2%                | PRE_GST_INVOICE
          Invoice dated 2016-11-20 and 2016-11-22                                 | PRE_GST_INVOICE
          Tax Invoice Invoice Date: 2016-01-10 VAT 5%                             | PRE_GST_INVOICE
          Packing slip Order 2021-01-01 Qty 2                                     | OTHER
          Invoice                                                                 | OTHER
          Tax Invoice Invoice Date: 2021-13-45                                    | TAX_INVOICE
          Delivery note VAT 5% dated 2016-01-10                                   | OTHER
          """)
  void classify_textEvidence_picksClass(String text, DocumentClass expected) {
    assertThat(classifier.classify(layer(text))).isEqualTo(expected);
  }

  @Test
  void classify_emptyLayer_isOther() {
    assertThat(classifier.classify(new TextLayer(List.of()))).isEqualTo(DocumentClass.OTHER);
  }

  @Test
  void classify_labelledInvoiceDate_winsOverLaterDates() {
    // The order and delivery dates are after GST start; the invoice date is not.
    String text =
        "Invoice Order Date: 2018-01-01 Invoice Date: 20 June 2017 Delivered 2018-02-01 VAT";

    assertThat(classifier.classify(layer(text))).isEqualTo(DocumentClass.PRE_GST_INVOICE);
  }

  @Test
  void classify_evidenceOnSeveralPages_isCombined() {
    var first = page(1, "Tax Invoice");
    var second = page(2, "GSTIN 29ABCDE1234F1Z5 Invoice Date: 2023-04-01");

    assertThat(classifier.classify(new TextLayer(List.of(first, second))))
        .isEqualTo(DocumentClass.TAX_INVOICE);
  }

  @Test
  void scores_withoutEvidence_areBelowMinimum() {
    assertThat(classifier.scores(layer("Hello world")).values())
        .allSatisfy(score -> assertThat(score).isLessThan(DocumentClassifier.MIN_SCORE));
  }

  private static DocumentClass expected(InvoiceType type) {
    return switch (type) {
      case GST_TAX_INVOICE -> DocumentClass.TAX_INVOICE;
      case CREDIT_NOTE -> DocumentClass.CREDIT_NOTE;
      case PRE_GST_INVOICE -> DocumentClass.PRE_GST_INVOICE;
    };
  }

  private static TextLayer layer(String text) {
    return new TextLayer(List.of(page(1, text)));
  }

  private static TextPage page(int pageNo, String text) {
    List<Word> words =
        Arrays.stream(text.strip().split("\\s+"))
            .map(word -> new Word(word, 0, 0, 1, 1, 1))
            .toList();
    return new TextPage(pageNo, 100, 100, TextSource.OCR, words);
  }
}
