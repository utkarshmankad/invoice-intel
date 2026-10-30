package dev.invoiceintel.common.extraction;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * One tax on a line item (specs.md §5.2).
 *
 * @param rate percentage, e.g. {@code 9} for 9%
 */
public record TaxLine(
    @NotNull @Valid Field<TaxType> type,
    @NotNull @Valid Field<BigDecimal> rate,
    @NotNull @Valid Field<BigDecimal> amount) {}
