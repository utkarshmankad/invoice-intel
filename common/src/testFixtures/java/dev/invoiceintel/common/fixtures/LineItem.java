package dev.invoiceintel.common.fixtures;

import java.math.BigDecimal;

/** One invoice line; amounts have scale 2 and {@code taxRate} is a percentage. */
public record LineItem(
    String description,
    String hsnCode,
    int quantity,
    BigDecimal unitPrice,
    BigDecimal netAmount,
    BigDecimal taxRate,
    BigDecimal taxAmount,
    BigDecimal totalAmount) {}
