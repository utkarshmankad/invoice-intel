package dev.invoiceintel.pii;

import dev.invoiceintel.common.DocumentId;
import java.util.Objects;

/**
 * What detectors know about the document besides its text (architecture.md §6.3). Seller-block
 * tagging (plan M2.3) adds the positions that detectors must leave alone.
 */
public record DocumentContext(DocumentId documentId) {

  public DocumentContext {
    Objects.requireNonNull(documentId, "documentId");
  }
}
