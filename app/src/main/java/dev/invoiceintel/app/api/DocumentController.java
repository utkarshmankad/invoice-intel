package dev.invoiceintel.app.api;

import dev.invoiceintel.common.DocumentClass;
import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.common.extraction.InvoiceExtraction;
import dev.invoiceintel.ingest.IngestService;
import dev.invoiceintel.pipeline.DocumentDetails;
import dev.invoiceintel.pipeline.DocumentDetailsReader;
import dev.invoiceintel.pipeline.DocumentState;
import dev.invoiceintel.pipeline.PipelineService;
import dev.invoiceintel.pipeline.Stage;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Document upload (ING-1..4), list (UC1), detail (UC2) and reprocess (NFR-5), architecture.md §6.1,
 * §8. No response carries document text, original files, page images or vault values; the
 * extraction holds pseudonymized values only (specs.md §6).
 */
@RestController
@RequestMapping("/api/documents")
@SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification = "Inventory marker, not a defect; /api/** requires admin auth (ADR-12)")
public class DocumentController {

  private final IngestService ingest;
  private final PipelineService pipeline;
  private final DocumentDetailsReader details;

  public DocumentController(
      IngestService ingest, PipelineService pipeline, DocumentDetailsReader details) {
    this.ingest = ingest;
    this.pipeline = pipeline;
    this.details = details;
  }

  /**
   * Uploads one or more files as multipart parts named {@code files}. Re-uploading the same bytes
   * returns the existing document with {@code duplicate: true} (ING-2). File names and part content
   * types are ignored; the type comes from content (ING-3). New documents start the pipeline;
   * duplicates don't.
   */
  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public List<UploadedDocument> upload(@RequestPart("files") List<MultipartFile> files) {
    var contents = files.stream().map(DocumentController::bytes).toList();
    var results = ingest.ingest(contents);
    results.stream().filter(r -> !r.duplicate()).forEach(r -> pipeline.start(r.documentId()));
    return results.stream()
        .map(r -> new UploadedDocument(r.documentId().value(), r.duplicate()))
        .toList();
  }

  /**
   * The newest documents, at most {@value IngestService#LIST_LIMIT}, optionally only those in
   * {@code status} (UC1, architecture.md §8). An unknown status is a 400.
   */
  @GetMapping
  public List<DocumentView> list(@RequestParam(required = false) DocumentState status) {
    var documents = status == null ? ingest.list() : ingest.listByStatus(status.name());
    return documents.stream()
        .map(d -> new DocumentView(d.documentId().value(), d.status()))
        .toList();
  }

  /**
   * Status, failure, current extraction (pseudonymized), VAL-8 decision and rule results of one
   * document (UC2, architecture.md §8). The CI smoke test polls its {@code status} (docs/ci.md §4).
   */
  @GetMapping("/{id}")
  public DocumentDetailView get(@PathVariable UUID id) {
    return details
        .find(new DocumentId(id))
        .map(DocumentDetailView::of)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  /**
   * Re-runs the document from {@code fromStage} (NFR-5, architecture.md §5.1): 202 once the stage
   * is queued, 404 for an unknown document, 409 if the document hasn't reached the stage or the
   * stage isn't built yet, 400 for an unknown or missing stage.
   */
  @PostMapping("/{id}/reprocess")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public ReprocessView reprocess(@PathVariable UUID id, @RequestParam Stage fromStage) {
    if (!pipeline.reprocess(new DocumentId(id), fromStage)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    return new ReprocessView(id, fromStage);
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

  /** A queued reprocess. */
  public record ReprocessView(UUID id, Stage fromStage) {}

  /**
   * One document's details; see {@link DocumentDetails} for which runs' outputs appear.
   *
   * @param failure set exactly when {@code status} is FAILED
   * @param extraction null until an Extract run succeeded
   * @param decision null until a Validate run succeeded
   */
  public record DocumentDetailView(
      UUID id,
      DocumentState status,
      DocumentClass documentClass,
      Failure failure,
      InvoiceExtraction extraction,
      DocumentDetails.Decision decision,
      List<DocumentDetails.RuleCheck> rules) {

    public DocumentDetailView {
      rules = List.copyOf(rules);
    }

    static DocumentDetailView of(DocumentDetails d) {
      return new DocumentDetailView(
          d.id().value(),
          d.state(),
          d.documentClass(),
          d.failedStage() == null ? null : new Failure(d.failedStage(), d.failureCode()),
          d.extraction(),
          d.decision(),
          d.rules());
    }
  }

  /** Where and why a document failed; codes only (PII-8). */
  public record Failure(Stage stage, String code) {}
}
