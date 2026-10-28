package dev.invoiceintel.ingest.internal;

import org.apache.tika.Tika;

/**
 * Detects a MIME type from magic bytes only (ING-3); file names and client-sent content types are
 * never consulted, so a renamed file can't pass as another type.
 */
public final class FileTypeDetector {

  private final Tika tika = new Tika();

  public String detect(byte[] content) {
    return tika.detect(content);
  }
}
