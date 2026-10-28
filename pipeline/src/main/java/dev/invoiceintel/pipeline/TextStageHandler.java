package dev.invoiceintel.pipeline;

import dev.invoiceintel.common.DocumentClass;
import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.pipeline.internal.TextStageRepository;
import dev.invoiceintel.storage.BlobStore;
import dev.invoiceintel.text.DocumentClassifier;
import dev.invoiceintel.text.DocumentTextService;
import dev.invoiceintel.text.PageImageStore;
import dev.invoiceintel.text.PageRenderer;
import dev.invoiceintel.text.PdfTextExtractor;
import dev.invoiceintel.text.TesseractOcr;
import dev.invoiceintel.text.TextExtractionException;
import dev.invoiceintel.text.TextLayer;
import dev.invoiceintel.text.TextLayerStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The Text stage, RECEIVED → TEXT_READY (TXT-1..4, architecture.md §5.2, §6.2): reads the original
 * from the raw bucket, stores page images, writes the {@code text_layer} row of this run and sets
 * the rule-based {@code document_class} hint.
 */
public final class TextStageHandler implements StageHandler {

  /** The original's {@code blob_ref} row or object is missing. */
  public static final String ORIGINAL_MISSING = "ORIGINAL_MISSING";

  /** The original couldn't be read as a PDF or image, or OCR failed. */
  public static final String TEXT_EXTRACTION_FAILED = "TEXT_EXTRACTION_FAILED";

  private static final Logger log = LoggerFactory.getLogger(TextStageHandler.class);

  /**
   * {@code invoice-intel.text.*} plus the raw bucket (architecture.md §10).
   *
   * @param tessdata folder holding the Tesseract {@code *.traineddata} files
   * @param minTextChars non-whitespace characters from which a PDF page counts as having text
   * @param maxConcurrentPageJobs documents rendered or OCR'd at once (§10.1)
   */
  public record Settings(
      String rawBucket,
      int dpi,
      List<String> ocrLanguages,
      Path tessdata,
      int minTextChars,
      int maxConcurrentPageJobs) {

    public Settings {
      Objects.requireNonNull(rawBucket, "rawBucket");
      ocrLanguages = List.copyOf(ocrLanguages);
      Objects.requireNonNull(tessdata, "tessdata");
    }
  }

  private final BlobStore blobStore;
  private final TextStageRepository documents;
  private final DocumentTextService text;
  private final TextLayerStore layers;
  private final DocumentClassifier classifier = new DocumentClassifier();

  public TextStageHandler(BlobStore blobStore, JdbcClient jdbc, Settings settings) {
    this.blobStore = Objects.requireNonNull(blobStore, "blobStore");
    this.documents = new TextStageRepository(jdbc);
    this.layers = new TextLayerStore(jdbc);
    this.text =
        new DocumentTextService(
            new PdfTextExtractor(settings.minTextChars()),
            new PageImageStore(
                new PageRenderer(settings.dpi()), blobStore, jdbc, settings.rawBucket()),
            new TesseractOcr(settings.tessdata(), settings.ocrLanguages()),
            settings.maxConcurrentPageJobs());
  }

  @Override
  public Stage stage() {
    return Stage.TEXT;
  }

  @Override
  public void run(DocumentId documentId, int runNo) {
    var original =
        documents
            .original(documentId)
            .orElseThrow(() -> new StageFailedException(ORIGINAL_MISSING));
    byte[] content =
        blobStore.get(original.key()).orElseThrow(() -> new StageFailedException(ORIGINAL_MISSING));
    TextLayer layer;
    try {
      layer = text.extract(documentId, original.mimeType(), content);
    } catch (TextExtractionException e) {
      throw new StageFailedException(TEXT_EXTRACTION_FAILED);
    }
    layers.save(documentId, runNo, layer);
    DocumentClass documentClass = classifier.classify(layer);
    documents.setDocumentClass(documentId, documentClass);
    // PipelineService has set documentId, stage and runNo in the MDC.
    log.atInfo()
        .addKeyValue("pages", layer.pages().size())
        .addKeyValue("documentClass", documentClass)
        .log("text layer stored");
  }
}
