package dev.invoiceintel.ingest;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.common.logging.LogContext;
import dev.invoiceintel.ingest.internal.DocumentRepository;
import dev.invoiceintel.ingest.internal.FileType;
import dev.invoiceintel.ingest.internal.FileTypeDetector;
import dev.invoiceintel.ingest.internal.Sha256;
import dev.invoiceintel.storage.BlobKey;
import dev.invoiceintel.storage.BlobStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Accepts uploaded files (architecture.md §6.1): detects the type from content (ING-3), dedupes by
 * SHA-256 (ING-2), stores the original in the raw bucket (ING-4) and creates the {@code document}
 * row in {@code RECEIVED}. The caller starts the pipeline for new documents (ingest may not depend
 * on pipeline, §4.1).
 */
public final class IngestService {

  /** Page size of the document list until the API gets paging (UC1). */
  public static final int LIST_LIMIT = 100;

  private static final Logger log = LoggerFactory.getLogger(IngestService.class);

  private final BlobStore blobStore;
  private final DocumentRepository documents;
  private final FileTypeDetector detector = new FileTypeDetector();
  private final String rawBucket;

  public IngestService(BlobStore blobStore, JdbcClient jdbc, String rawBucket) {
    this.blobStore = Objects.requireNonNull(blobStore, "blobStore");
    this.documents = new DocumentRepository(jdbc);
    this.rawBucket = Objects.requireNonNull(rawBucket, "rawBucket");
  }

  /**
   * Ingests every file, or none: all types are checked before anything is stored, so a batch with
   * one unsupported file leaves no partial upload behind.
   *
   * @throws UnsupportedFileTypeException if any file is not a PDF, JPEG or PNG
   */
  public List<IngestResult> ingest(List<byte[]> files) {
    var types = new ArrayList<FileType>(files.size());
    for (int i = 0; i < files.size(); i++) {
      int index = i;
      String detected = detector.detect(files.get(i));
      types.add(
          FileType.ofMimeType(detected)
              .orElseThrow(() -> new UnsupportedFileTypeException(index, detected)));
    }
    var results = new ArrayList<IngestResult>(files.size());
    for (int i = 0; i < files.size(); i++) {
      results.add(ingestOne(files.get(i), types.get(i)));
    }
    return List.copyOf(results);
  }

  /** Status of the document, or empty if there is none with {@code id}. */
  public Optional<DocumentStatus> find(DocumentId id) {
    return documents.findStatus(id).map(status -> new DocumentStatus(id, status));
  }

  /** Newest documents first, at most {@value #LIST_LIMIT}, across all statuses. */
  public List<DocumentStatus> list() {
    return documents.list(null, LIST_LIMIT);
  }

  /** Newest documents in {@code status} first, at most {@value #LIST_LIMIT}. */
  public List<DocumentStatus> listByStatus(String status) {
    return documents.list(Objects.requireNonNull(status, "status"), LIST_LIMIT);
  }

  private IngestResult ingestOne(byte[] content, FileType type) {
    String sha256 = Sha256.hex(content);
    var existing = documents.findIdBySha256(sha256);
    if (existing.isPresent()) {
      return logged(new IngestResult(existing.get(), true));
    }
    DocumentId id = DocumentId.random();
    BlobKey original = new BlobKey(rawBucket, "raw/" + id + "/original." + type.extension());
    // Blob first: a document row must never point at a missing original.
    blobStore.put(original, content, type.mimeType());
    if (documents.insertReceived(id, sha256, type.mimeType(), original)) {
      return logged(new IngestResult(id, false));
    }
    // A concurrent upload of the same bytes won the unique index; keep its document.
    blobStore.delete(original);
    return logged(new IngestResult(documents.findIdBySha256(sha256).orElseThrow(), true));
  }

  private static IngestResult logged(IngestResult result) {
    try (var _ = LogContext.forDocument(result.documentId().value())) {
      log.atInfo().addKeyValue("duplicate", result.duplicate()).log("document ingested");
    }
    return result;
  }
}
