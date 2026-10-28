package dev.invoiceintel.ingest;

import dev.invoiceintel.common.DocumentId;
import java.util.Objects;

/** A document's ID and pipeline status (architecture.md §5.1); no content (PII-8). */
public record DocumentStatus(DocumentId documentId, String status) {

  public DocumentStatus {
    Objects.requireNonNull(documentId, "documentId");
    Objects.requireNonNull(status, "status");
  }
}
