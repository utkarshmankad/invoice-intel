package dev.invoiceintel.pipeline;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.pii.DocumentContext;
import dev.invoiceintel.pii.ImageRedactor;
import dev.invoiceintel.pii.PiiDetectors;
import dev.invoiceintel.pii.PiiSpan;
import dev.invoiceintel.pii.PiiVault;
import dev.invoiceintel.pii.RedactedContent;
import dev.invoiceintel.pii.RedactedPdf;
import dev.invoiceintel.pii.RedactionMode;
import dev.invoiceintel.pii.RedactionStore;
import dev.invoiceintel.pii.SpanMerger;
import dev.invoiceintel.pii.TextRedactor;
import dev.invoiceintel.pii.TokenGenerator;
import dev.invoiceintel.pipeline.internal.PiiStageRepository;
import dev.invoiceintel.storage.BlobKey;
import dev.invoiceintel.storage.BlobStore;
import dev.invoiceintel.text.ImagePreprocessor;
import dev.invoiceintel.text.TextExtractionException;
import dev.invoiceintel.text.TextLayer;
import dev.invoiceintel.text.TextLayerStore;
import dev.invoiceintel.text.TextPage;
import dev.invoiceintel.text.TextSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The PII stage, TEXT_READY → PII_CLEAN (PII-1..5, architecture.md §5.2, §6.3): detects and merges
 * spans on the latest text layer, redacts the text, masks the page images, rebuilds the PDF from
 * them and stores everything through {@link RedactionStore}. Every output is written in the stage
 * transaction, so a failed run leaves no spans, vault entries or redacted rows, and a document
 * without a successful PII run never gets a {@code RedactedContent} (§6.4).
 *
 * <p>Page images are masked on the image the word boxes were measured on: the stored render for
 * PDF-text pages, and for OCR pages the stored image preprocessed again, which gives the same
 * deskewed image the Text stage OCR'd (preprocessing is deterministic).
 */
public final class PiiStageHandler implements StageHandler {

  /** The document has no text layer. */
  public static final String TEXT_LAYER_MISSING = "TEXT_LAYER_MISSING";

  /** A page of the text layer has no stored page image. */
  public static final String PAGE_IMAGE_MISSING = "PAGE_IMAGE_MISSING";

  /** Spans, text layer and images didn't fit together, or an image couldn't be read. */
  public static final String REDACTION_FAILED = "REDACTION_FAILED";

  private static final Logger log = LoggerFactory.getLogger(PiiStageHandler.class);

  /**
   * @param redactedBucket bucket of the redacted pages and PDF (§9.1)
   * @param dpi resolution of the stored page images, which sets the redacted PDF's page size
   */
  public record Settings(String redactedBucket, int dpi) {

    public Settings {
      Objects.requireNonNull(redactedBucket, "redactedBucket");
    }
  }

  private final BlobStore blobStore;
  private final PiiStageRepository documents;
  private final TextLayerStore layers;
  private final PiiDetectors detectors;
  private final SpanMerger merger = new SpanMerger();
  private final TextRedactor textRedactor;
  private final ImageRedactor imageRedactor = new ImageRedactor();
  private final RedactedPdf redactedPdf;
  private final RedactionStore store;
  private final RedactionMode mode;
  private volatile ImagePreprocessor preprocessor;

  public PiiStageHandler(
      BlobStore blobStore,
      JdbcClient jdbc,
      PiiDetectors detectors,
      TokenGenerator tokens,
      PiiVault vault,
      RedactionMode mode,
      Settings settings) {
    this.blobStore = Objects.requireNonNull(blobStore, "blobStore");
    this.documents = new PiiStageRepository(jdbc);
    this.layers = new TextLayerStore(jdbc);
    this.detectors = Objects.requireNonNull(detectors, "detectors");
    this.textRedactor = new TextRedactor(tokens, vault);
    this.redactedPdf = new RedactedPdf(settings.dpi());
    this.store = new RedactionStore(blobStore, jdbc, settings.redactedBucket());
    this.mode = Objects.requireNonNull(mode, "mode");
  }

  @Override
  public Stage stage() {
    return Stage.PII;
  }

  @Override
  public void run(DocumentId documentId, int runNo) {
    TextLayer layer =
        layers.latest(documentId).orElseThrow(() -> new StageFailedException(TEXT_LAYER_MISSING));
    Map<Integer, byte[]> images = pageImages(documentId, layer);
    try {
      DocumentContext ctx = DocumentContext.of(documentId, layer);
      List<PiiSpan> spans = merger.merge(layer, detectors.detect(layer, ctx));
      TextRedactor.Result redaction = textRedactor.redact(layer, spans, ctx, mode);
      RedactedContent content = imageRedactor.redact(redaction, layer, images);
      store.save(documentId, runNo, layer, redaction, content, redactedPdf.build(content));
      // PipelineService has set documentId, stage and runNo in the MDC.
      log.atInfo()
          .addKeyValue("pages", layer.pages().size())
          .addKeyValue("spans", redaction.spans().size())
          .addKeyValue("vaultEntries", redaction.vaultEntries().size())
          .addKeyValue("mode", mode)
          .log("redacted content stored");
    } catch (IllegalArgumentException e) {
      throw new StageFailedException(REDACTION_FAILED);
    }
  }

  /** The image each page's word boxes were measured on, by page number. */
  private Map<Integer, byte[]> pageImages(DocumentId documentId, TextLayer layer) {
    Map<Integer, BlobKey> keys = documents.pageImages(documentId);
    Map<Integer, byte[]> images = new HashMap<>();
    for (TextPage page : layer.pages()) {
      BlobKey key = keys.get(page.pageNo());
      if (key == null) {
        throw new StageFailedException(PAGE_IMAGE_MISSING);
      }
      byte[] stored =
          blobStore.get(key).orElseThrow(() -> new StageFailedException(PAGE_IMAGE_MISSING));
      images.put(page.pageNo(), page.source() == TextSource.OCR ? preprocess(stored) : stored);
    }
    return images;
  }

  private byte[] preprocess(byte[] stored) {
    try {
      return preprocessor().preprocess(stored).png();
    } catch (TextExtractionException e) {
      throw new StageFailedException(REDACTION_FAILED);
    }
  }

  // OpenCV's native library loads on first use, so digital PDFs never need it.
  private ImagePreprocessor preprocessor() {
    ImagePreprocessor current = preprocessor;
    if (current == null) {
      synchronized (this) {
        current = preprocessor;
        if (current == null) {
          current = new ImagePreprocessor();
          preprocessor = current;
        }
      }
    }
    return current;
  }
}
