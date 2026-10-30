/**
 * Extraction schema records (spec §5, EXT-1): the shape the Extract stage produces and the Validate
 * stage checks. Values are pseudonymized only (specs §6). Shared here so {@code validate} needn't
 * depend on {@code extract} (architecture.md §4.1, ADR-19).
 */
package dev.invoiceintel.common.extraction;
