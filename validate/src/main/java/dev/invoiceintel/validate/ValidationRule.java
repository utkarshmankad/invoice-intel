package dev.invoiceintel.validate;

import dev.invoiceintel.common.extraction.InvoiceExtraction;

/**
 * One check on an extraction (architecture.md §6.6). Implementations are pure: no I/O, no logging
 * of field values (PII-8).
 */
public interface ValidationRule {

  /** The spec ID the rule implements, e.g. {@code "VAL-2"}; unique within a {@link RuleEngine}. */
  String id();

  RuleResult check(InvoiceExtraction extraction, ValidationContext context);
}
