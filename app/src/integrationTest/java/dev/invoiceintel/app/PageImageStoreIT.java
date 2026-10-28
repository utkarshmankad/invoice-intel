package dev.invoiceintel.app;

import static dev.invoiceintel.app.TestcontainersConfiguration.RAW_BUCKET;
import static org.assertj.core.api.Assertions.assertThat;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.common.fixtures.InvoiceType;
import dev.invoiceintel.common.fixtures.SyntheticInvoiceGenerator;
import dev.invoiceintel.ingest.IngestService;
import dev.invoiceintel.storage.BlobKey;
import dev.invoiceintel.storage.BlobStore;
import dev.invoiceintel.text.PageImageStore;
import dev.invoiceintel.text.PageRenderer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/** Page images in the raw bucket with PAGE_IMAGE rows: TXT-2, architecture.md §6.2, §9.1. */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestAdminConfiguration.class})
class PageImageStoreIT {

  @Autowired JdbcClient jdbc;
  @Autowired BlobStore blobStore;
  @Autowired IngestService ingest;

  private PageImageStore store;

  @BeforeEach
  void setUp() {
    jdbc.sql("DELETE FROM document").update();
    store = new PageImageStore(new PageRenderer(), blobStore, jdbc, RAW_BUCKET);
  }

  @Test
  void renderAndStore_syntheticInvoice_storesPngAt300DpiAndReferencesIt() throws IOException {
    byte[] pdf =
        SyntheticInvoiceGenerator.toPdf(
            SyntheticInvoiceGenerator.generate(InvoiceType.GST_TAX_INVOICE, 11));
    DocumentId id = ingested(pdf);

    List<BlobKey> keys = store.renderAndStore(id, pdf);

    BlobKey expected = new BlobKey(RAW_BUCKET, "raw/" + id + "/pages/1.png");
    assertThat(keys).containsExactly(expected);
    BufferedImage image = ImageIO.read(new ByteArrayInputStream(blobStore.get(expected).get()));
    assertThat(image.getWidth()).isEqualTo(2480);
    assertThat(image.getHeight()).isEqualTo(3507);
    assertThat(pageRows(id)).containsExactly(Map.entry(1, expected.key()));
  }

  @Test
  void renderAndStore_runAgain_keepsOneRowPerPage() {
    byte[] pdf =
        SyntheticInvoiceGenerator.toPdf(
            SyntheticInvoiceGenerator.generate(InvoiceType.CREDIT_NOTE, 12));
    DocumentId id = ingested(pdf);
    var lowRes = new PageImageStore(new PageRenderer(72), blobStore, jdbc, RAW_BUCKET);

    List<BlobKey> first = lowRes.renderAndStore(id, pdf);
    List<BlobKey> second = lowRes.renderAndStore(id, pdf);

    assertThat(second).isEqualTo(first);
    assertThat(pageRows(id)).containsExactly(Map.entry(1, first.getFirst().key()));
  }

  private DocumentId ingested(byte[] pdf) {
    return ingest.ingest(List.of(pdf)).getFirst().documentId();
  }

  private List<Map.Entry<Integer, String>> pageRows(DocumentId id) {
    return jdbc.sql(
            """
            SELECT page_no, key FROM blob_ref
            WHERE document_id = ? AND kind = 'PAGE_IMAGE' AND bucket = ?
            ORDER BY page_no
            """)
        .params(id.value(), RAW_BUCKET)
        .query((rs, row) -> Map.entry(rs.getInt("page_no"), rs.getString("key")))
        .list();
  }
}
