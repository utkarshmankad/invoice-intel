package dev.invoiceintel.pipeline;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.common.extraction.InvoiceExtraction;
import java.util.Objects;

/**
 * A reviewed document's current extraction, for the label export (REV-4, architecture.md §6.8,
 * ADR-31). Values are pseudonymized: the PII token fields hold the PII stage's tokens.
 *
 * @param id the document
 * @param sha256 the SHA-256 of the original's bytes, the label's key (ING-2)
 * @param extraction the latest succeeded Extract run's extraction with the review corrections
 *     applied
 */
public record ReviewedExtraction(DocumentId id, String sha256, InvoiceExtraction extraction) {

  public ReviewedExtraction {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(sha256, "sha256");
    Objects.requireNonNull(extraction, "extraction");
  }

  // Not the record default: the extraction holds field values (PII-8).
  @Override
  public String toString() {
    return "ReviewedExtraction[id=" + id + "]";
  }
}
