package dev.invoiceintel.reconcile;

/**
 * Turns a real order number into its pseudonym (PII-3). {@code app} backs it with the PII module's
 * token generator, so imported rows join the {@code orderNumber} tokens of extractions; reconcile
 * itself never sees the token key (architecture.md §4.1, §6.7).
 */
@FunctionalInterface
public interface OrderTokenizer {

  /** The token of {@code orderNumber}, e.g. {@code ORDER_NUMBER_K3QZ7F2A}. */
  String token(String orderNumber);
}
