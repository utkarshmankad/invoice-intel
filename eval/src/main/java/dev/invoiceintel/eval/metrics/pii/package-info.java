/**
 * PII recall and precision (EVAL-2, specs §6.3): locates label spans in page text and scores
 * detected spans against them with ≥ 50% character coverage (architecture.md §6.9). Types here
 * mirror {@code dev.invoiceintel.pii}, which {@code eval} can't import (§4.1). Nothing in this
 * package logs or prints span text (PII-8).
 */
package dev.invoiceintel.eval.metrics.pii;
