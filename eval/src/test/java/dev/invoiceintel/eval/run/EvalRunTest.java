package dev.invoiceintel.eval.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.invoiceintel.common.extraction.InvoiceExtraction;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvalRunTest {

  @TempDir Path dir;

  @Test
  void execute_stubRunner_skipsEveryDocument() {
    List<LabelledDocument> documents = Dataset.load(SyntheticDataset.write(dir)).documents();

    List<DocumentRecord> records = EvalRun.execute(documents, new StubDocumentRunner());

    assertThat(records)
        .extracting(DocumentRecord::sha256)
        .containsExactlyElementsOf(SyntheticDataset.ALL);
    assertThat(records)
        .allSatisfy(
            r -> {
              assertThat(r.status()).isEqualTo(DocumentResult.Status.SKIPPED);
              assertThat(r.synthetic()).isTrue();
              assertThat(r.latencyMs()).isNotNegative();
              assertThat(r.error()).isEmpty();
            });
  }

  @Test
  void execute_runnerThrows_recordsFailureWithClassNameOnly() {
    List<LabelledDocument> documents = Dataset.load(SyntheticDataset.write(dir)).documents();
    DocumentRunner runner =
        new DocumentRunner() {
          @Override
          public String name() {
            return "throwing";
          }

          @Override
          public DocumentResult run(LabelledDocument document) {
            if (document.sha256().equals(SyntheticDataset.TAX_INVOICE)) {
              throw new IllegalStateException("text that must not be recorded");
            }
            return DocumentResult.extracted(emptyExtraction());
          }
        };

    List<DocumentRecord> records = EvalRun.execute(documents, runner);

    assertThat(records)
        .extracting(DocumentRecord::status, DocumentRecord::error)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(DocumentResult.Status.EXTRACTED, ""),
            org.assertj.core.groups.Tuple.tuple(
                DocumentResult.Status.FAILED, "IllegalStateException"));
  }

  @Test
  void documentResult_factories_setStatusAndExtraction() {
    InvoiceExtraction extraction = emptyExtraction();

    assertThat(DocumentResult.extracted(extraction).extraction()).containsSame(extraction);
    assertThat(DocumentResult.skipped().status()).isEqualTo(DocumentResult.Status.SKIPPED);
    assertThat(DocumentResult.skipped().extraction()).isEmpty();
    assertThat(DocumentResult.failed().status()).isEqualTo(DocumentResult.Status.FAILED);
    assertThat(DocumentResult.failed().extraction()).isEmpty();
    assertThatThrownBy(() -> DocumentResult.extracted(null))
        .isInstanceOf(NullPointerException.class);
  }

  private static InvoiceExtraction emptyExtraction() {
    return new InvoiceExtraction(
        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null);
  }
}
