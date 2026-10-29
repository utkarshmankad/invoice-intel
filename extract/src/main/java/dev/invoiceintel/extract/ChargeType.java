package dev.invoiceintel.extract;

/**
 * What a line item charges for (specs.md §5.2): shipping and convenience charges are line items
 * with their own type.
 */
public enum ChargeType {
  /** Goods or services ordered. */
  PRODUCT,
  SHIPPING,
  CONVENIENCE,
  /** Any other fee printed as its own line. */
  OTHER
}
