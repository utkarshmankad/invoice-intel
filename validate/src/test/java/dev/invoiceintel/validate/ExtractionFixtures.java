package dev.invoiceintel.validate;

import dev.invoiceintel.common.DocumentClass;
import dev.invoiceintel.common.extraction.ChargeType;
import dev.invoiceintel.common.extraction.Currency;
import dev.invoiceintel.common.extraction.Field;
import dev.invoiceintel.common.extraction.FieldSource;
import dev.invoiceintel.common.extraction.InvoiceExtraction;
import dev.invoiceintel.common.extraction.LineItem;
import dev.invoiceintel.common.extraction.TaxLine;
import dev.invoiceintel.common.extraction.TaxType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Synthetic, already pseudonymized extractions for rule tests (testing.md). */
final class ExtractionFixtures {

  private ExtractionFixtures() {}

  /** An intra-state tax invoice whose amounts add up: 1000.00 + 9% CGST + 9% SGST = 1180.00. */
  static InvoiceExtraction consistentTaxInvoice() {
    TaxLine cgst = new TaxLine(Field.llm(TaxType.CGST, 0.95), rate("9"), money("90"));
    TaxLine sgst = new TaxLine(Field.llm(TaxType.SGST, 0.95), rate("9"), money("90"));
    LineItem item =
        new LineItem(
            Field.llm(1, 1.0),
            Field.llm(ChargeType.PRODUCT, 0.9),
            Field.llm("Steel water bottle, 1 L", 0.9),
            Field.llm("73239310", 0.85),
            money("1000"),
            Field.llm(BigDecimal.ONE, 0.95),
            null,
            money("1000"),
            List.of(cgst, sgst),
            money("1180"));
    return new InvoiceExtraction(
        Field.llm(DocumentClass.TAX_INVOICE, 0.95),
        Field.llm("INVNO_TOKEN_01", 0.9),
        Field.llm(LocalDate.of(2025, 3, 14), 0.9),
        Field.llm("ORDNO_TOKEN_01", 0.9),
        null,
        Field.llm("Example Traders", 0.8),
        null,
        null,
        null,
        Field.llm("Maharashtra", 0.9),
        null,
        Field.llm("Maharashtra", 0.9),
        null,
        new Field<>(Currency.INR, 1.0, FieldSource.RULE, null),
        money("1000"),
        money("180"),
        money("1180"),
        null,
        null,
        List.of(item));
  }

  private static Field<BigDecimal> money(String amount) {
    return Field.llm(new BigDecimal(amount).setScale(2), 0.9);
  }

  private static Field<BigDecimal> rate(String percent) {
    return Field.llm(new BigDecimal(percent), 0.95);
  }
}
