package dev.invoiceintel.common.extraction;

import dev.invoiceintel.common.DocumentClass;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The extraction schema v1 (specs.md §5, EXT-1): the LLM's structured output and the shape of the
 * {@code extraction.result} JSONB column (architecture.md §7). Values are pseudonymized: invoice
 * and order numbers are spec tokens (§6), never the real ones.
 *
 * <p>Bean Validation checks only what holds for every document class: required fields, confidence
 * range, at least one line. Rules that depend on the class or era ({@code sellerGstin} and {@code
 * placeOfSupply} on GST invoices, {@code originalInvoiceRef} on credit notes) and all arithmetic
 * belong to the validate module (VAL-*).
 */
public record InvoiceExtraction(
    @NotNull @Valid Field<DocumentClass> documentClass,
    @NotNull @Valid Field<String> invoiceNumber,
    @NotNull @Valid Field<LocalDate> invoiceDate,
    @NotNull @Valid Field<String> orderNumber,
    @Valid Field<LocalDate> orderDate,
    @NotNull @Valid Field<String> sellerName,
    @Valid Field<String> sellerGstin,
    @Valid Field<String> sellerPan,
    @Valid Field<String> sellerAddress,
    @Valid Field<String> placeOfSupply,
    @Valid Field<String> placeOfDelivery,
    @NotNull @Valid Field<String> buyerState,
    @Valid Field<Boolean> reverseCharge,
    @NotNull @Valid Field<Currency> currency,
    @NotNull @Valid Field<BigDecimal> subtotal,
    @NotNull @Valid Field<BigDecimal> totalTax,
    @NotNull @Valid Field<BigDecimal> grandTotal,
    @Valid Field<String> amountInWords,
    @Valid Field<String> originalInvoiceRef,
    @NotEmpty List<@Valid LineItem> lineItems) {

  public InvoiceExtraction {
    // A null list stays null so Bean Validation reports it; a null element fails the parse.
    lineItems = lineItems == null ? null : List.copyOf(lineItems);
  }
}
