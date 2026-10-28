package dev.invoiceintel.app.api;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.ingest.IngestService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Document upload (ING-1..4) and status (UC2), architecture.md §6.1, §8. */
@RestController
@RequestMapping("/api/documents")
@SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification = "Inventory marker, not a defect; /api/** requires admin auth (ADR-12)")
public class DocumentController {

  private final IngestService ingest;

  public DocumentController(IngestService ingest) {
    this.ingest = ingest;
  }

  /**
   * Uploads one or more files as multipart parts named {@code files}. Re-uploading the same bytes
   * returns the existing document with {@code duplicate: true} (ING-2). File names and part content
   * types are ignored; the type comes from content (ING-3).
   */
  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public List<UploadedDocument> upload(@RequestPart("files") List<MultipartFile> files) {
    var contents = files.stream().map(DocumentController::bytes).toList();
    return ingest.ingest(contents).stream()
        .map(r -> new UploadedDocument(r.documentId().value(), r.duplicate()))
        .toList();
  }

  /** The newest documents, optionally only those in {@code status} (UC1, architecture.md §8). */
  @GetMapping
  public List<DocumentView> list(@RequestParam(required = false) String status) {
    var documents = status == null ? ingest.list() : ingest.listByStatus(status);
    return documents.stream()
        .map(d -> new DocumentView(d.documentId().value(), d.status()))
        .toList();
  }

  /**
   * Status of one document. Until the pipeline adds decisions and extractions (UC2) this is the ID
   * and status only; the CI smoke test polls it (docs/ci.md §4).
   */
  @GetMapping("/{id}")
  public DocumentView get(@PathVariable UUID id) {
    return ingest
        .find(new DocumentId(id))
        .map(d -> new DocumentView(d.documentId().value(), d.status()))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  private static byte[] bytes(MultipartFile file) {
    try {
      return file.getBytes();
    } catch (IOException e) {
      throw new UncheckedIOException("could not read upload", e);
    }
  }

  /** One uploaded file's document. */
  public record UploadedDocument(UUID id, boolean duplicate) {}

  /** A document's status. */
  public record DocumentView(UUID id, String status) {}
}
