package dev.invoiceintel.ingest;

import dev.invoiceintel.common.DocumentId;
import java.util.Objects;

/**
 * Outcome of ingesting one file: the document it belongs to and whether that document already
 * existed because the same bytes were uploaded before (ING-2).
 */
public record IngestResult(DocumentId documentId, boolean duplicate) {

  public IngestResult {
    Objects.requireNonNull(documentId, "documentId");
  }
}
