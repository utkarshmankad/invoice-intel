package dev.invoiceintel.extract;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

/**
 * One invoice line (specs.md §5.2). {@code hsnSac} is required on GST-era invoices; that rule
 * depends on the document class and date, so the validate module checks it, not Bean Validation.
 */
public record LineItem(
    @NotNull @Valid Field<Integer> lineNo,
    @NotNull @Valid Field<ChargeType> chargeType,
    @NotNull @Valid Field<String> description,
    @Valid Field<String> hsnSac,
    @NotNull @Valid Field<BigDecimal> unitPrice,
    @NotNull @Valid Field<BigDecimal> quantity,
    @Valid Field<BigDecimal> discount,
    @NotNull @Valid Field<BigDecimal> netAmount,
    @NotEmpty List<@Valid TaxLine> taxLines,
    @NotNull @Valid Field<BigDecimal> lineTotal) {

  public LineItem {
    // A null list stays null so Bean Validation reports it; a null element fails the parse.
    taxLines = taxLines == null ? null : List.copyOf(taxLines);
  }
}
