package dev.invoiceintel.validate;

import dev.invoiceintel.common.extraction.InvoiceExtraction;
import dev.invoiceintel.common.extraction.LineItem;
import dev.invoiceintel.common.extraction.TaxLine;
import dev.invoiceintel.common.extraction.TaxType;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * VAL-5: an intra-state supply (seller state = place of supply) carries CGST plus SGST or UTGST; an
 * inter-state supply carries IGST only. Checked per line, on lines that carry any of those four
 * taxes; CESS, VAT and CST are ignored (VAL-6 checks the tax regime).
 *
 * <p>The seller state comes from a valid seller GSTIN, else from {@code sellerAddress} (as in
 * VAL-4). UTGST is accepted in place of SGST in every state, since invoices print either for union
 * territories. An invoice without those taxes passes (exempt goods, pre-GST); missing or unknown
 * seller state or place of supply on a taxed invoice is a WARN.
 */
public final class TaxSplitRule implements ValidationRule {

  static final String ID = "VAL-5";

  private static final Set<TaxType> SPLIT_TYPES =
      EnumSet.of(TaxType.CGST, TaxType.SGST, TaxType.UTGST, TaxType.IGST);

  @Override
  public String id() {
    return ID;
  }

  @Override
  public RuleResult check(InvoiceExtraction extraction, ValidationContext context) {
    boolean taxed = extraction.lineItems().stream().anyMatch(line -> !splitTypes(line).isEmpty());
    if (!taxed) {
      return RuleResult.pass();
    }
    Optional<GstState> seller = GstinStateRule.sellerState(extraction);
    if (seller.isEmpty()) {
      return RuleResult.warn(
          "seller state unknown (sellerGstin, sellerAddress); split not checked");
    }
    if (extraction.placeOfSupply() == null) {
      return RuleResult.warn("placeOfSupply is missing; split not checked");
    }
    Optional<GstState> supply = GstState.resolve(extraction.placeOfSupply().value());
    if (supply.isEmpty()) {
      return RuleResult.warn("placeOfSupply is not a known state; split not checked");
    }
    boolean intraState = seller.get() == supply.get();
    String states =
        " (seller state " + seller.get().code() + ", place of supply " + supply.get().code() + ")";
    for (LineItem line : extraction.lineItems()) {
      Set<TaxType> types = splitTypes(line);
      if (types.isEmpty() || fits(types, intraState)) {
        continue;
      }
      String expected =
          intraState
              ? "intra-state supply needs CGST and SGST/UTGST"
              : "inter-state supply needs IGST only";
      return RuleResult.fail(
          "line " + line.lineNo().value() + ": " + expected + states + ", found " + types);
    }
    return RuleResult.pass();
  }

  private static boolean fits(Set<TaxType> types, boolean intraState) {
    if (intraState) {
      return !types.contains(TaxType.IGST)
          && types.contains(TaxType.CGST)
          && (types.contains(TaxType.SGST) || types.contains(TaxType.UTGST));
    }
    return types.equals(EnumSet.of(TaxType.IGST));
  }

  private static Set<TaxType> splitTypes(LineItem line) {
    Set<TaxType> types = EnumSet.noneOf(TaxType.class);
    for (TaxLine tax : line.taxLines()) {
      if (SPLIT_TYPES.contains(tax.type().value())) {
        types.add(tax.type().value());
      }
    }
    return types;
  }
}
