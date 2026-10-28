package dev.invoiceintel.common.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import dev.invoiceintel.common.fixtures.SyntheticInvoice.TaxType;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.LongStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SyntheticInvoiceGeneratorTest {

  private static final long SEED = 20261028L;

  @ParameterizedTest
  @EnumSource(InvoiceType.class)
  void toPdf_eachType_hasOnePageWithTextLayerOfGroundTruth(InvoiceType type) throws IOException {
    SyntheticInvoice invoice = SyntheticInvoiceGenerator.generate(type, SEED);

    String text = textOf(SyntheticInvoiceGenerator.toPdf(invoice));

    assertThat(text)
        .contains(SyntheticInvoiceGenerator.title(type))
        .contains(invoice.invoiceNumber())
        .contains(invoice.invoiceDate().toString())
        .contains(invoice.seller().taxId())
        .contains(invoice.seller().pan())
        .contains(invoice.grandTotal().toPlainString())
        .contains(invoice.amountInWords());
    assertThat(text).contains(invoice.piiValues());
    invoice.lineItems().forEach(item -> assertThat(text).contains(item.hsnCode()));
  }

  @ParameterizedTest
  @EnumSource(InvoiceType.class)
  void generate_anySeed_totalsAddUp(InvoiceType type) {
    LongStream.range(0, 50)
        .mapToObj(seed -> SyntheticInvoiceGenerator.generate(type, seed))
        .forEach(
            invoice -> {
              BigDecimal lines =
                  invoice.lineItems().stream()
                      .map(LineItem::totalAmount)
                      .reduce(BigDecimal.ZERO, BigDecimal::add);
              assertThat(lines).isEqualByComparingTo(invoice.grandTotal());
              assertThat(invoice.netTotal().add(invoice.taxTotal()))
                  .isEqualByComparingTo(invoice.grandTotal());
              invoice
                  .lineItems()
                  .forEach(
                      item ->
                          assertThat(item.unitPrice().multiply(BigDecimal.valueOf(item.quantity())))
                              .isEqualByComparingTo(item.netAmount()));
            });
  }

  @ParameterizedTest
  @EnumSource(InvoiceType.class)
  void generate_anySeed_taxTypeFollowsStatesAndEra(InvoiceType type) {
    LongStream.range(0, 50)
        .mapToObj(seed -> SyntheticInvoiceGenerator.generate(type, seed))
        .forEach(
            invoice -> {
              boolean intraState = invoice.seller().state() == invoice.buyer().state();
              TaxType expected =
                  type == InvoiceType.PRE_GST_INVOICE
                      ? (intraState ? TaxType.VAT : TaxType.CST)
                      : (intraState ? TaxType.CGST_SGST : TaxType.IGST);
              assertThat(invoice.taxType()).isEqualTo(expected);
            });
  }

  @Test
  void generate_manySeeds_coversIntraAndInterState() {
    assertThat(
            LongStream.range(0, 20)
                .mapToObj(
                    seed -> SyntheticInvoiceGenerator.generate(InvoiceType.GST_TAX_INVOICE, seed))
                .map(SyntheticInvoice::taxType))
        .contains(TaxType.CGST_SGST, TaxType.IGST);
  }

  @Test
  void generate_gstInvoice_isDatedFromGstStartWithValidGstin() {
    SyntheticInvoice invoice =
        SyntheticInvoiceGenerator.generate(InvoiceType.GST_TAX_INVOICE, SEED);

    assertThat(invoice.invoiceDate()).isAfterOrEqualTo(SyntheticInvoiceGenerator.GST_START);
    String gstin = invoice.seller().taxId();
    assertThat(gstin).hasSize(15).contains(invoice.seller().pan());
    assertThat(gstin.charAt(14)).isEqualTo(FakeIds.gstinCheckChar(gstin.substring(0, 14)));
    assertThat(invoice.creditedInvoiceNumber()).isEmpty();
  }

  @Test
  void generate_preGstInvoice_isDatedBeforeGstWithTin() {
    SyntheticInvoice invoice =
        SyntheticInvoiceGenerator.generate(InvoiceType.PRE_GST_INVOICE, SEED);

    assertThat(invoice.invoiceDate()).isBefore(SyntheticInvoiceGenerator.GST_START);
    assertThat(invoice.seller().taxId()).matches(invoice.seller().state().code() + "[0-9]{9}");
  }

  @Test
  void generate_creditNote_referencesEarlierInvoice() {
    SyntheticInvoice note = SyntheticInvoiceGenerator.generate(InvoiceType.CREDIT_NOTE, SEED);

    assertThat(note.invoiceNumber()).startsWith("CN-");
    assertThat(note.creditedInvoiceNumber())
        .hasValueSatisfying(n -> assertThat(n).startsWith("INV-"));
    assertThat(note.invoiceDate()).isAfter(note.orderDate());
    assertThat(note.lineItems()).hasSize(1);
    assertThat(note.piiValues()).contains(note.originalInvoiceNumber());
  }

  @ParameterizedTest
  @EnumSource(InvoiceType.class)
  void toPdf_sameSeed_producesIdenticalBytes(InvoiceType type) {
    byte[] first = SyntheticInvoiceGenerator.toPdf(SyntheticInvoiceGenerator.generate(type, SEED));
    byte[] second = SyntheticInvoiceGenerator.toPdf(SyntheticInvoiceGenerator.generate(type, SEED));

    assertThat(first).isEqualTo(second);
  }

  @Test
  void generate_differentSeeds_produceDifferentDocuments() {
    assertThat(SyntheticInvoiceGenerator.generate(InvoiceType.GST_TAX_INVOICE, 1))
        .isNotEqualTo(SyntheticInvoiceGenerator.generate(InvoiceType.GST_TAX_INVOICE, 2));
  }

  @Test
  void writePdf_newFolder_writesReadablePdf(@TempDir Path dir) throws IOException {
    Path target = dir.resolve("nested/credit-note.pdf");

    SyntheticInvoice invoice =
        SyntheticInvoiceGenerator.writePdf(InvoiceType.CREDIT_NOTE, SEED, target);

    assertThat(textOf(Files.readAllBytes(target))).contains(invoice.invoiceNumber());
  }

  private static String textOf(byte[] pdf) throws IOException {
    try (PDDocument document = Loader.loadPDF(pdf)) {
      assertThat(document.getNumberOfPages()).isEqualTo(1);
      return new PDFTextStripper().getText(document);
    }
  }
}
