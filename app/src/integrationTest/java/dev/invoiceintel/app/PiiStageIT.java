package dev.invoiceintel.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.common.fixtures.InvoiceType;
import dev.invoiceintel.common.fixtures.SyntheticInvoice;
import dev.invoiceintel.common.fixtures.SyntheticInvoiceGenerator;
import dev.invoiceintel.ingest.IngestService;
import dev.invoiceintel.pipeline.PiiStageHandler;
import dev.invoiceintel.pipeline.PipelineService;
import dev.invoiceintel.pipeline.Stage;
import dev.invoiceintel.storage.BlobKey;
import dev.invoiceintel.storage.BlobStore;
import dev.invoiceintel.text.PageRenderer;
import dev.invoiceintel.text.PdfTextExtractor;
import dev.invoiceintel.text.TextLayerStore;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * The real PII stage after the real Text stage (PII-1, PII-3, PII-4, PII-5, architecture.md §5.2,
 * §6.3, §7): spans, vault entries, redacted pages and PDF are stored with the move to PII_CLEAN,
 * and a failed run stores none of them.
 */
@SpringBootTest(
    properties = {
      "jobrunr.background-job-server.enabled=true",
      "jobrunr.background-job-server.poll-interval-in-seconds=5"
    })
@ActiveProfiles("test")
@Import({
  TestcontainersConfiguration.class,
  TestAdminConfiguration.class,
  TestPiiKeysConfiguration.class
})
class PiiStageIT {

  private static final Duration TIMEOUT = Duration.ofSeconds(90);

  @Autowired IngestService ingest;
  @Autowired PipelineService pipeline;
  @Autowired BlobStore blobStore;
  @Autowired JdbcClient jdbc;

  @Test
  void digitalPdf_reachesPiiCleanWithSpansVaultAndRedactedBlobs() {
    SyntheticInvoice invoice = SyntheticInvoiceGenerator.generate(InvoiceType.GST_TAX_INVOICE, 61);
    DocumentId id = upload(SyntheticInvoiceGenerator.toPdf(invoice));

    awaitStatus(id, "PII_CLEAN");

    assertThat(spanRows(id, 1)).isNotEmpty();
    assertThat(spanTypes(id)).contains("ORDER_NUMBER", "PERSON");
    // No raw value in any span column (PII-3).
    String stored = String.join("\n", spanRows(id, 1));
    for (String value : invoice.piiValues()) {
      assertThat(stored).doesNotContain(value);
    }
    // Pseudonymize mode: every token has a vault entry, markers (PIN codes) have none.
    assertThat(tokensWithoutVaultEntry(id)).isZero();
    assertThat(blobRows(id)).containsExactly("REDACTED_PAGE 1", "REDACTED_PDF null");
    byte[] pdf = blobStore.get(redactedPdfKey(id)).orElseThrow();
    assertThat(new PdfTextExtractor().extract(pdf).pages())
        .singleElement()
        .satisfies(page -> assertThat(page.words()).isEmpty());
  }

  @Test
  void photo_ocrPage_isMaskedAndStored() {
    var png = new AtomicReference<byte[]>();
    new PageRenderer()
        .render(
            SyntheticInvoiceGenerator.toPdf(
                SyntheticInvoiceGenerator.generate(InvoiceType.GST_TAX_INVOICE, 2024)),
            (image, pageNo) -> png.set(image));
    DocumentId id = upload(png.get());

    awaitStatus(id, "PII_CLEAN");

    assertThat(spanTypes(id)).contains("ORDER_NUMBER");
    assertThat(blobRows(id)).containsExactly("REDACTED_PAGE 1", "REDACTED_PDF null");
  }

  @Test
  void reprocess_writesSpansPerRunAndReusesVaultEntries() {
    DocumentId id =
        upload(
            SyntheticInvoiceGenerator.toPdf(
                SyntheticInvoiceGenerator.generate(InvoiceType.CREDIT_NOTE, 62)));
    awaitStatus(id, "PII_CLEAN");

    assertThat(pipeline.reprocess(id, Stage.PII)).isTrue();

    await().atMost(TIMEOUT).until(() -> !spanRows(id, 2).isEmpty());
    awaitStatus(id, "PII_CLEAN");
    assertThat(spanRows(id, 2)).hasSameSizeAs(spanRows(id, 1));
    // The same tokens again: ON CONFLICT keeps the existing entries instead of failing the run.
    assertThat(tokensWithoutVaultEntry(id)).isZero();
    assertThat(blobRows(id)).containsExactly("REDACTED_PAGE 1", "REDACTED_PDF null");
  }

  @Test
  void noTextLayer_failsWithoutOutputsAndStops() {
    DocumentId id = textReadyDocument();

    assertThat(pipeline.reprocess(id, Stage.PII)).isTrue();

    awaitStatus(id, "FAILED");
    assertThat(failure(id)).isEqualTo("PII " + PiiStageHandler.TEXT_LAYER_MISSING);
    assertNoOutputs(id);
  }

  @Test
  void missingPageImage_failsWithoutOutputs() {
    DocumentId id = textReadyDocument();
    new TextLayerStore(jdbc)
        .save(
            id,
            1,
            new PdfTextExtractor()
                .extract(
                    SyntheticInvoiceGenerator.toPdf(
                        SyntheticInvoiceGenerator.generate(InvoiceType.GST_TAX_INVOICE, 63))));

    assertThat(pipeline.reprocess(id, Stage.PII)).isTrue();

    awaitStatus(id, "FAILED");
    assertThat(failure(id)).isEqualTo("PII " + PiiStageHandler.PAGE_IMAGE_MISSING);
    assertNoOutputs(id);
  }

  private void assertNoOutputs(DocumentId id) {
    assertThat(spanTypes(id)).isEmpty();
    assertThat(blobRows(id)).isEmpty();
    assertThat(
            jdbc.sql("SELECT stage || ' ' || status FROM stage_run WHERE document_id = ?")
                .param(id.value())
                .query(String.class)
                .list())
        .containsExactly("PII FAILED");
  }

  /** Ingests and starts the pipeline, as {@code DocumentController} does for new uploads. */
  private DocumentId upload(byte[] content) {
    DocumentId id = ingest.ingest(List.of(content)).getFirst().documentId();
    pipeline.start(id);
    return id;
  }

  /** A document that reached TEXT_READY without the Text stage, so it has no outputs of its own. */
  private DocumentId textReadyDocument() {
    DocumentId id = DocumentId.random();
    byte[] hash = new byte[32];
    ThreadLocalRandom.current().nextBytes(hash);
    jdbc.sql("INSERT INTO document (id, sha256, mime_type, status) VALUES (?, ?, ?, 'TEXT_READY')")
        .params(id.value(), HexFormat.of().formatHex(hash), "application/pdf")
        .update();
    return id;
  }

  private void awaitStatus(DocumentId id, String expected) {
    await()
        .atMost(TIMEOUT)
        .until(
            () ->
                jdbc.sql("SELECT status FROM document WHERE id = ?")
                    .param(id.value())
                    .query(String.class)
                    .single()
                    .equals(expected));
  }

  private String failure(DocumentId id) {
    return jdbc.sql("SELECT failed_stage || ' ' || failure_code FROM document WHERE id = ?")
        .param(id.value())
        .query(String.class)
        .single();
  }

  private List<String> spanRows(DocumentId id, int runNo) {
    return jdbc.sql(
            """
            SELECT concat_ws(' ', page_no, span_start, span_end, bbox::text, type, detector, token)
            FROM pii_span WHERE document_id = ? AND run_no = ? ORDER BY page_no, span_start
            """)
        .params(id.value(), runNo)
        .query(String.class)
        .list();
  }

  private List<String> spanTypes(DocumentId id) {
    return jdbc.sql("SELECT DISTINCT type FROM pii_span WHERE document_id = ?")
        .param(id.value())
        .query(String.class)
        .list();
  }

  private long tokensWithoutVaultEntry(DocumentId id) {
    return jdbc.sql(
            """
            SELECT count(*) FROM pii_span s
            WHERE s.document_id = ? AND s.token NOT LIKE '[REDACTED:%'
              AND NOT EXISTS (SELECT 1 FROM pii_vault v WHERE v.token = s.token)
            """)
        .param(id.value())
        .query(Long.class)
        .single();
  }

  private List<String> blobRows(DocumentId id) {
    return jdbc.sql(
            """
            SELECT kind || ' ' || coalesce(page_no::text, 'null') FROM blob_ref
            WHERE document_id = ? AND kind IN ('REDACTED_PAGE', 'REDACTED_PDF') ORDER BY kind, page_no
            """)
        .param(id.value())
        .query(String.class)
        .list();
  }

  private BlobKey redactedPdfKey(DocumentId id) {
    return jdbc.sql(
            "SELECT bucket, key FROM blob_ref WHERE document_id = ? AND kind = 'REDACTED_PDF'")
        .param(id.value())
        .query((rs, row) -> new BlobKey(rs.getString("bucket"), rs.getString("key")))
        .single();
  }
}
