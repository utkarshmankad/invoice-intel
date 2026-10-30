package dev.invoiceintel.validate;

import dev.invoiceintel.common.extraction.Field;
import dev.invoiceintel.common.extraction.InvoiceExtraction;
import dev.invoiceintel.common.extraction.LineItem;
import dev.invoiceintel.common.extraction.TaxLine;
import dev.invoiceintel.common.extraction.TaxType;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * VAL-6: an invoice dated before {@link #GST_START} must not carry GST fields; one dated on or
 * after it must not carry VAT/CST.
 *
 * <ul>
 *   <li>Pre-GST with a CGST/SGST/IGST/UTGST line, or GST era with a VAT/CST line: FAIL.
 *   <li>Pre-GST with a {@code sellerGstin} but no GST tax line: WARN. GSTINs didn't exist on
 *       invoices then, so it points at a misread date or field, but the taxes themselves are
 *       consistent with the era.
 *   <li>{@code CESS} is allowed in both eras: service-tax cesses existed before GST, and the schema
 *       doesn't tell the two apart.
 *   <li>No invoice date: WARN when the document carries any era-specific field (the rule can't
 *       check it), otherwise PASS.
 * </ul>
 *
 * <p>Messages name the invoice date, tax types and field names only (PII-8).
 */
public final class TaxRegimeDateRule implements ValidationRule {

  /** First day of the GST regime; this date itself is GST era. */
  public static final LocalDate GST_START = LocalDate.of(2017, 7, 1);

  static final String ID = "VAL-6";

  private static final Set<TaxType> GST_TYPES =
      EnumSet.of(TaxType.CGST, TaxType.SGST, TaxType.IGST, TaxType.UTGST);
  private static final Set<TaxType> PRE_GST_TYPES = EnumSet.of(TaxType.VAT, TaxType.CST);

  @Override
  public String id() {
    return ID;
  }

  @Override
  public RuleResult check(InvoiceExtraction extraction, ValidationContext context) {
    Set<TaxType> types = taxTypes(extraction);
    Set<TaxType> gst = intersect(types, GST_TYPES);
    Set<TaxType> preGst = intersect(types, PRE_GST_TYPES);
    boolean hasGstin = extraction.sellerGstin() != null;

    Field<LocalDate> dateField = extraction.invoiceDate();
    if (dateField == null) {
      if (gst.isEmpty() && preGst.isEmpty() && !hasGstin) {
        return RuleResult.pass();
      }
      return RuleResult.warn("invoiceDate missing; tax regime can't be checked");
    }

    LocalDate date = dateField.value();
    if (date.isBefore(GST_START)) {
      if (!gst.isEmpty()) {
        return RuleResult.fail(
            "invoiceDate " + date + " is before GST (" + GST_START + ") but has " + names(gst));
      }
      if (hasGstin) {
        return RuleResult.warn(
            "invoiceDate " + date + " is before GST (" + GST_START + ") but has sellerGstin");
      }
      return RuleResult.pass();
    }
    if (!preGst.isEmpty()) {
      return RuleResult.fail(
          "invoiceDate "
              + date
              + " is in the GST era (from "
              + GST_START
              + ") but has "
              + names(preGst));
    }
    return RuleResult.pass();
  }

  private static Set<TaxType> taxTypes(InvoiceExtraction extraction) {
    // The extraction passed Bean Validation, so lines, tax lines and their types are present.
    Set<TaxType> types = EnumSet.noneOf(TaxType.class);
    for (LineItem item : extraction.lineItems()) {
      for (TaxLine tax : item.taxLines()) {
        types.add(tax.type().value());
      }
    }
    return types;
  }

  private static Set<TaxType> intersect(Set<TaxType> types, Set<TaxType> of) {
    Set<TaxType> result = EnumSet.copyOf(of);
    result.retainAll(types);
    return result;
  }

  private static String names(Set<TaxType> types) {
    return types.stream().map(TaxType::name).collect(Collectors.joining(", "));
  }
}
