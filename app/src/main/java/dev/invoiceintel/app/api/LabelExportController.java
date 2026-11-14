package dev.invoiceintel.app.api;

import dev.invoiceintel.pipeline.ReviewedExtraction;
import dev.invoiceintel.pipeline.ReviewedExtractionReader;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Label export (REV-4, architecture.md §6.8, §8, ADR-31): every {@code REVIEWED} document as a
 * pseudonymized eval label, in one ZIP of {@code <sha256>.json} files that unpacks straight into a
 * labels folder. Documents whose extraction a valid label can't hold are listed in {@value
 * #SKIPPED} by document ID and JSON path, never by value. Token fields hold the PII stage's tokens
 * and no PII text is written, so nothing here reads the vault (PII-7).
 */
@RestController
@RequestMapping("/api/labels")
@SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification = "Inventory marker, not a defect; /api/** requires admin auth (ADR-12)")
public class LabelExportController {

  /** The ZIP entry listing documents left out; not {@code .json}, so the eval doesn't load it. */
  static final String SKIPPED = "SKIPPED.txt";

  static final String FILE_NAME = "labels-export.zip";

  private final ReviewedExtractionReader reviewed;

  public LabelExportController(ReviewedExtractionReader reviewed) {
    this.reviewed = reviewed;
  }

  /** The reviewed documents' labels as a ZIP download. */
  @GetMapping(value = "/export", produces = "application/zip")
  public ResponseEntity<byte[]> export() {
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/zip"))
        .cacheControl(CacheControl.noStore())
        .header(
            "Content-Disposition",
            ContentDisposition.attachment().filename(FILE_NAME).build().toString())
        .body(zip());
  }

  private byte[] zip() {
    var bytes = new ByteArrayOutputStream();
    var skipped = new StringBuilder();
    try (var zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
      for (ReviewedExtraction document : reviewed.reviewed()) {
        byte[] label;
        try {
          label =
              ExportLabel.JSON
                  .writerWithDefaultPrettyPrinter()
                  .writeValueAsBytes(ExportLabel.of(document));
        } catch (ExportLabel.NotExportable e) {
          skipped.append(document.id().value()).append('\t').append(e.getMessage()).append('\n');
          continue;
        }
        zip.putNextEntry(new ZipEntry(document.sha256() + ".json"));
        zip.write(label);
        zip.closeEntry();
      }
      if (!skipped.isEmpty()) {
        zip.putNextEntry(new ZipEntry(SKIPPED));
        zip.write(skipped.toString().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return bytes.toByteArray();
  }
}
