package dev.invoiceintel.eval.run;

import dev.invoiceintel.common.extraction.Field;
import dev.invoiceintel.common.extraction.InvoiceExtraction;
import java.math.BigDecimal;
import java.util.Set;

/**
 * A runner with fixed, made-up outcomes for the synthetic labels (tests only): the tax invoice is
 * extracted exactly and auto-approved, the bill of supply is auto-approved with a wrong grand total
 * (a false auto-approval).
 */
public final class FixtureDocumentRunner implements DocumentRunner {

  public static final String NAME = "fixture";
  public static final String MODEL = "fixture-model-1";
  public static final String PROMPT = "extract-text/v1";

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public DocumentResult run(LabelledDocument document) {
    InvoiceExtraction exact = LabelExtractions.from(document.label());
    if (document.sha256().equals(SyntheticDataset.TAX_INVOICE)) {
      return DocumentResult.extracted(exact, Decision.AUTO_APPROVED, usage("0.002400"));
    }
    InvoiceExtraction wrongTotal =
        new InvoiceExtraction(
            exact.documentClass(),
            exact.invoiceNumber(),
            exact.invoiceDate(),
            exact.orderNumber(),
            exact.orderDate(),
            exact.sellerName(),
            exact.sellerGstin(),
            exact.sellerPan(),
            exact.sellerAddress(),
            exact.placeOfSupply(),
            exact.placeOfDelivery(),
            exact.buyerState(),
            exact.reverseCharge(),
            exact.currency(),
            exact.subtotal(),
            exact.totalTax(),
            Field.llm(new BigDecimal("300.00"), 0.95),
            exact.amountInWords(),
            exact.originalInvoiceRef(),
            exact.lineItems());
    return DocumentResult.extracted(wrongTotal, Decision.AUTO_APPROVED, usage("0.001600"));
  }

  private static LlmUsage usage(String costUsd) {
    return new LlmUsage(new BigDecimal(costUsd), Set.of(MODEL), Set.of(PROMPT));
  }
}
