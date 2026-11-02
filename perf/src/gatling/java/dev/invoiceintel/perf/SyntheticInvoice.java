package dev.invoiceintel.perf;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The synthetic smoke invoice (app integration-test fixture, docs/ci.md §4), made unique per
 * upload. Uploads are deduplicated by SHA-256 of the bytes (ING-2), so every new document gets a
 * PDF comment line with a random UUID after {@code %%EOF}; PDF readers ignore it and the text layer
 * stays the same, so the LLM stub's fixed extraction still fits.
 */
final class SyntheticInvoice {

  static final String FILE_NAME = "gst-tax-invoice.pdf";

  private static final byte[] PDF = load();

  private SyntheticInvoice() {}

  /** The fixture as is; uploading it twice returns the same document. */
  static byte[] fixed() {
    return PDF.clone();
  }

  /** A copy no earlier upload has: a new document every call. */
  static byte[] unique() {
    byte[] suffix = ("\n%" + UUID.randomUUID() + "\n").getBytes(StandardCharsets.US_ASCII);
    byte[] out = new byte[PDF.length + suffix.length];
    System.arraycopy(PDF, 0, out, 0, PDF.length);
    System.arraycopy(suffix, 0, out, PDF.length, suffix.length);
    return out;
  }

  private static byte[] load() {
    try (InputStream in = SyntheticInvoice.class.getResourceAsStream("/" + FILE_NAME)) {
      if (in == null) {
        throw new IllegalStateException(FILE_NAME + " not on the classpath");
      }
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
