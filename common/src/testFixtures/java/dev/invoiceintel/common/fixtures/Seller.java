package dev.invoiceintel.common.fixtures;

/**
 * Fake seller. {@code taxId} is a GSTIN for GST documents and a VAT TIN for pre-GST invoices.
 * Seller details are not PII (spec §6.1).
 */
public record Seller(
    String name, String street, String pinCode, GstState state, String pan, String taxId) {}
