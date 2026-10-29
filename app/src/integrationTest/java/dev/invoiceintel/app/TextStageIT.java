package dev.invoiceintel.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.common.fixtures.InvoiceType;
import dev.invoiceintel.common.fixtures.SyntheticInvoice;
import dev.invoiceintel.common.fixtures.SyntheticInvoiceGenerator;
import dev.invoiceintel.ingest.IngestService;
import dev.invoiceintel.pipeline.PipelineService;
import dev.invoiceintel.pipeline.Stage;
import dev.invoiceintel.pipeline.TextStageHandler;
import dev.invoiceintel.text.PageRenderer;
import dev.invoiceintel.text.TextLayer;
import dev.invoiceintel.text.TextLayerStore;
import dev.invoiceintel.text.TextPage;
import dev.invoiceintel.text.TextSource;
import dev.invoiceintel.text.Word;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * The real Text stage on uploaded synthetic fixtures (TXT-1..4, architecture.md §5.2, §6.2).
 * Documents go on through the PII stage and stop in PII_CLEAN, the last stage with a handler; the
 * PII outputs are checked in {@link PiiStageIT}.
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
class TextStageIT {

  private static final Duration TIMEOUT = Duration.ofSeconds(90);

  @Autowired IngestService ingest;
  @Autowired PipelineService pipeline;
  @Autowired JdbcClient jdbc;

  @Test
  void digitalPdf_reachesTextReadyWithPdfTextLayerAndClass() {
    SyntheticInvoice invoice = SyntheticInvoiceGenerator.generate(InvoiceType.GST_TAX_INVOICE, 51);
    DocumentId id = upload(SyntheticInvoiceGenerator.toPdf(invoice));

    awaitStatus(id, "PII_CLEAN");

    TextLayer layer = new TextLayerStore(jdbc).latest(id).orElseThrow();
    assertThat(layer.pages()).hasSize(1);
    TextPage page = layer.pages().getFirst();
    assertThat(page.source()).isEqualTo(TextSource.PDF_TEXT);
    assertThat(page.words()).extracting(Word::text).contains(invoice.invoiceNumber());
    assertThat(documentClass(id)).isEqualTo("TAX_INVOICE");
    assertThat(pageImages(id)).containsExactly(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"png", "jpg"})
  void photo_isStoredAsPageOneAndOcrd(String format) throws IOException {
    DocumentId id = upload(photo(InvoiceType.CREDIT_NOTE, format.equals("png") ? 52 : 54, format));

    awaitStatus(id, "PII_CLEAN");

    TextPage page = new TextLayerStore(jdbc).latest(id).orElseThrow().pages().getFirst();
    assertThat(page.source()).isEqualTo(TextSource.OCR);
    assertThat(page.words()).extracting(Word::text).contains("Credit");
    assertThat(documentClass(id)).isEqualTo("CREDIT_NOTE");
    assertThat(pageImages(id)).containsExactly(1);
  }

  @Test
  void scannedPdf_pageWithoutTextLayer_isOcrd() throws IOException {
    DocumentId id = upload(scannedPdf(photo(InvoiceType.GST_TAX_INVOICE, 55, "png")));

    awaitStatus(id, "PII_CLEAN");

    TextPage page = new TextLayerStore(jdbc).latest(id).orElseThrow().pages().getFirst();
    assertThat(page.source()).isEqualTo(TextSource.OCR);
    assertThat(documentClass(id)).isEqualTo("TAX_INVOICE");
    assertThat(pageImages(id)).containsExactly(1);
  }

  @Test
  void unreadablePdf_failsTextStage() {
    byte[] broken = "%PDF-1.7\nnot really a pdf\n".getBytes(StandardCharsets.US_ASCII);
    DocumentId id = upload(broken);

    awaitStatus(id, "FAILED");

    assertThat(
            jdbc.sql("SELECT failed_stage || ' ' || failure_code FROM document WHERE id = ?")
                .param(id.value())
                .query(String.class)
                .single())
        .isEqualTo("TEXT " + TextStageHandler.TEXT_EXTRACTION_FAILED);
    assertThat(new TextLayerStore(jdbc).latest(id)).isEmpty();
  }

  @Test
  void reprocess_writesNewLayerRowPerRun() {
    DocumentId id =
        upload(
            SyntheticInvoiceGenerator.toPdf(
                SyntheticInvoiceGenerator.generate(InvoiceType.PRE_GST_INVOICE, 53)));
    awaitStatus(id, "PII_CLEAN");

    assertThat(pipeline.reprocess(id, Stage.TEXT)).isTrue();

    await().atMost(TIMEOUT).until(() -> layerRuns(id).equals(List.of(1, 2)));
    awaitStatus(id, "PII_CLEAN");
    assertThat(documentClass(id)).isEqualTo("PRE_GST_INVOICE");
    assertThat(pageImages(id)).containsExactly(1);
  }

  /** A fixture rendered at 300 DPI and encoded as {@code format}, like a phone photo or scan. */
  private static byte[] photo(InvoiceType type, long seed, String format) throws IOException {
    var png = new AtomicReference<byte[]>();
    new PageRenderer()
        .render(
            SyntheticInvoiceGenerator.toPdf(SyntheticInvoiceGenerator.generate(type, seed)),
            (image, pageNo) -> png.set(image));
    if (format.equals("png")) {
      return png.get();
    }
    BufferedImage image = ImageIO.read(new ByteArrayInputStream(png.get()));
    var out = new ByteArrayOutputStream();
    ImageIO.write(image, format, out);
    return out.toByteArray();
  }

  /** An A4 PDF whose only content is {@code png}, so it has no text layer. */
  private static byte[] scannedPdf(byte[] png) throws IOException {
    try (var document = new PDDocument()) {
      var page = new PDPage(PDRectangle.A4);
      document.addPage(page);
      var image = PDImageXObject.createFromByteArray(document, png, "scan.png");
      try (var content = new PDPageContentStream(document, page)) {
        content.drawImage(image, 0, 0, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
      }
      var out = new ByteArrayOutputStream();
      document.save(out);
      return out.toByteArray();
    }
  }

  /** Ingests and starts the pipeline, as {@code DocumentController} does for new uploads. */
  private DocumentId upload(byte[] content) {
    DocumentId id = ingest.ingest(List.of(content)).getFirst().documentId();
    pipeline.start(id);
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

  private String documentClass(DocumentId id) {
    return jdbc.sql("SELECT document_class FROM document WHERE id = ?")
        .param(id.value())
        .query(String.class)
        .single();
  }

  private List<Integer> pageImages(DocumentId id) {
    return jdbc.sql(
            "SELECT page_no FROM blob_ref WHERE document_id = ? AND kind = 'PAGE_IMAGE' ORDER BY 1")
        .param(id.value())
        .query(Integer.class)
        .list();
  }

  private List<Integer> layerRuns(DocumentId id) {
    return jdbc.sql("SELECT run_no FROM text_layer WHERE document_id = ? ORDER BY run_no")
        .param(id.value())
        .query(Integer.class)
        .list();
  }
}
