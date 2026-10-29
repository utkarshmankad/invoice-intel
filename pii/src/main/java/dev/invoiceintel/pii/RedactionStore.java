package dev.invoiceintel.pii;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.pii.internal.PiiSpanRepository;
import dev.invoiceintel.pii.internal.PiiVaultRepository;
import dev.invoiceintel.pii.internal.RedactedBlobRepository;
import dev.invoiceintel.storage.BlobKey;
import dev.invoiceintel.storage.BlobStore;
import dev.invoiceintel.text.TextLayer;
import dev.invoiceintel.text.TextPage;
import dev.invoiceintel.text.Word;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Persists the outputs of one PII run (PII-1, PII-3, PII-4, PII-5, architecture.md §5.2, §6.3, §7):
 * a {@code pii_span} row per replaced span, the vault entries, the masked page images as {@code
 * redacted/{documentId}/pages/{n}.png} and the rebuilt PDF as {@code
 * redacted/{documentId}/document.pdf}, each with its {@code blob_ref} row (§9.1).
 *
 * <p>The caller runs {@link #save} inside the stage transaction, so the rows commit with the
 * document's move to {@code PII_CLEAN} or not at all. Blobs are written before their rows and are
 * replaced under the same keys by a re-run, as the Text stage does with page images.
 */
public final class RedactionStore {

  private final BlobStore blobStore;
  private final PiiSpanRepository spans;
  private final PiiVaultRepository vault;
  private final RedactedBlobRepository blobs;
  private final String redactedBucket;

  public RedactionStore(BlobStore blobStore, JdbcClient jdbc, String redactedBucket) {
    this.blobStore = Objects.requireNonNull(blobStore, "blobStore");
    this.spans = new PiiSpanRepository(jdbc);
    this.vault = new PiiVaultRepository(jdbc);
    this.blobs = new RedactedBlobRepository(jdbc);
    this.redactedBucket = Objects.requireNonNull(redactedBucket, "redactedBucket");
  }

  /**
   * Stores PII run {@code runNo} of a document.
   *
   * @param layer the text layer the redaction was made from (for the span boxes)
   * @param redaction the {@link TextRedactor} result (spans, replacements, vault entries)
   * @param content the content with masked page images ({@link ImageRedactor})
   * @param pdf the redacted PDF ({@link RedactedPdf})
   * @throws IllegalArgumentException if a page of {@code content} has no masked image
   */
  public void save(
      DocumentId documentId,
      int runNo,
      TextLayer layer,
      TextRedactor.Result redaction,
      RedactedContent content,
      byte[] pdf) {
    Objects.requireNonNull(documentId, "documentId");
    Objects.requireNonNull(pdf, "pdf");
    Map<Integer, PageText> texts = new HashMap<>();
    for (TextPage page : layer.pages()) {
      texts.put(page.pageNo(), PageText.of(page));
    }
    List<PiiSpan> kept = redaction.spans();
    for (int i = 0; i < kept.size(); i++) {
      PiiSpan span = kept.get(i);
      spans.insert(
          documentId,
          runNo,
          new PiiSpanRepository.Row(
              span.pageNo(),
              span.start(),
              span.end(),
              box(texts.get(span.pageNo()).wordsIn(span.start(), span.end())),
              span.type().name(),
              span.detector(),
              redaction.replacements().get(i)));
    }
    for (VaultEntry entry : redaction.vaultEntries()) {
      vault.insertIfAbsent(entry.token(), entry.type().name(), entry.ciphertext());
    }
    for (RedactedContent.Page page : content.pages()) {
      byte[] png =
          page.image()
              .orElseThrow(
                  () -> new IllegalArgumentException("page " + page.pageNo() + " has no image"));
      BlobKey key = key("pages/" + page.pageNo() + ".png", documentId);
      blobStore.put(key, png, "image/png");
      blobs.upsertPage(documentId, page.pageNo(), key);
    }
    BlobKey pdfKey = key("document.pdf", documentId);
    blobStore.put(pdfKey, pdf, "application/pdf");
    blobs.upsertPdf(documentId, pdfKey);
  }

  private BlobKey key(String name, DocumentId documentId) {
    return new BlobKey(redactedBucket, "redacted/" + documentId + "/" + name);
  }

  // Merged spans always cover at least one word (SpanMerger), so the box is never empty.
  static PiiSpanRepository.Box box(List<Word> words) {
    double left = Double.MAX_VALUE;
    double top = Double.MAX_VALUE;
    double right = -Double.MAX_VALUE;
    double bottom = -Double.MAX_VALUE;
    for (Word word : words) {
      left = Math.min(left, word.x());
      top = Math.min(top, word.y());
      right = Math.max(right, word.x() + word.w());
      bottom = Math.max(bottom, word.y() + word.h());
    }
    return new PiiSpanRepository.Box(left, top, right - left, bottom - top);
  }
}
