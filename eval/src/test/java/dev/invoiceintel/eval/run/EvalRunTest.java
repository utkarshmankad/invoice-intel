package dev.invoiceintel.eval.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.invoiceintel.common.extraction.InvoiceExtraction;
import dev.invoiceintel.eval.metrics.fields.HeaderField;
import dev.invoiceintel.eval.metrics.fields.LineItemCounts;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvalRunTest {

  @TempDir Path dir;

  @Test
  void execute_stubRunner_skipsEveryDocument() {
    List<LabelledDocument> documents = Dataset.load(SyntheticDataset.write(dir)).documents();

    List<DocumentRecord> records = EvalRun.execute(documents, new StubDocumentRunner()).records();

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
              assertThat(r.decision()).isNull();
              assertThat(r.scored()).isFalse();
              assertThat(r.correct()).isFalse();
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
            return DocumentResult.extracted(
                LabelExtractions.from(document.label()), Decision.NEEDS_REVIEW, LlmUsage.NONE);
          }
        };

    List<DocumentRecord> records = EvalRun.execute(documents, runner).records();

    assertThat(records)
        .extracting(DocumentRecord::status, DocumentRecord::error)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(DocumentResult.Status.EXTRACTED, ""),
            org.assertj.core.groups.Tuple.tuple(
                DocumentResult.Status.FAILED, "IllegalStateException"));
    assertThat(records.get(1).decision()).isEqualTo(Decision.FAILED);
    assertThat(records.get(1).scored()).isFalse();
  }

  @Test
  void execute_fixtureRunner_scoresExtractionsAndSumsUsage() {
    List<LabelledDocument> documents = Dataset.load(SyntheticDataset.write(dir)).documents();

    EvalRun.Result result = EvalRun.execute(documents, new FixtureDocumentRunner());

    DocumentRecord billOfSupply = result.records().get(0);
    DocumentRecord taxInvoice = result.records().get(1);
    assertThat(billOfSupply.mismatchedFields()).containsExactly(HeaderField.GRAND_TOTAL);
    assertThat(billOfSupply.correct()).isFalse();
    assertThat(billOfSupply.decision()).isEqualTo(Decision.AUTO_APPROVED);
    assertThat(taxInvoice.mismatchedFields()).isEmpty();
    assertThat(taxInvoice.lineItems()).isEqualTo(new LineItemCounts(2, 0, 0));
    assertThat(taxInvoice.correct()).isTrue();
    assertThat(taxInvoice.costUsd()).isEqualByComparingTo("0.0024");
    assertThat(result.usage().costUsd()).isEqualByComparingTo("0.004");
    assertThat(result.usage().modelIds()).containsExactly(FixtureDocumentRunner.MODEL);
    assertThat(result.usage().promptVersions()).containsExactly(FixtureDocumentRunner.PROMPT);
  }

  @Test
  void execute_runnerTokenizesLabelNumbers_comparesTokens() {
    List<LabelledDocument> documents =
        Dataset.load(SyntheticDataset.write(dir)).documents().stream()
            .filter(d -> d.sha256().equals(SyntheticDataset.TAX_INVOICE))
            .toList();
    DocumentRunner runner =
        new DocumentRunner() {
          @Override
          public String name() {
            return "tokenizing";
          }

          @Override
          public DocumentResult run(LabelledDocument document) {
            // The extraction keeps printed numbers, so tokenized labels no longer match them.
            return DocumentResult.extracted(
                LabelExtractions.from(document.label()), Decision.NEEDS_REVIEW, LlmUsage.NONE);
          }

          @Override
          public String labelToken(String printed) {
            return "TOKEN_" + printed.length();
          }
        };

    DocumentRecord record = EvalRun.execute(documents, runner).records().get(0);

    assertThat(record.mismatchedFields())
        .containsExactlyInAnyOrder(HeaderField.INVOICE_NUMBER, HeaderField.ORDER_NUMBER);
  }

  @Test
  void documentResult_factories_setStatusAndExtraction() {
    InvoiceExtraction extraction = emptyExtraction();
    LlmUsage usage = new LlmUsage(BigDecimal.ONE, Set.of("m"), Set.of("p/v1"));

    DocumentResult extracted = DocumentResult.extracted(extraction, Decision.AUTO_APPROVED, usage);
    assertThat(extracted.extraction()).containsSame(extraction);
    assertThat(extracted.decision()).contains(Decision.AUTO_APPROVED);
    assertThat(extracted.usage()).isEqualTo(usage);
    assertThat(DocumentResult.skipped().decision()).isEmpty();
    assertThat(DocumentResult.failed(usage).usage()).isEqualTo(usage);
    assertThat(DocumentResult.failed().decision()).contains(Decision.FAILED);
    assertThatThrownBy(() -> DocumentResult.extracted(extraction, Decision.FAILED, usage))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(DocumentResult.skipped().status()).isEqualTo(DocumentResult.Status.SKIPPED);
    assertThat(DocumentResult.skipped().extraction()).isEmpty();
    assertThat(DocumentResult.failed().status()).isEqualTo(DocumentResult.Status.FAILED);
    assertThat(DocumentResult.failed().extraction()).isEmpty();
    assertThatThrownBy(() -> DocumentResult.extracted(null, Decision.AUTO_APPROVED, usage))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void llmUsage_negativeCost_throws() {
    assertThatThrownBy(() -> new LlmUsage(new BigDecimal("-0.01"), Set.of(), Set.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void documentRecord_scoreHalfPresent_throws() {
    assertThatThrownBy(
            () ->
                new DocumentRecord(
                    "a",
                    true,
                    DocumentResult.Status.EXTRACTED,
                    Decision.AUTO_APPROVED,
                    Set.of(),
                    null,
                    BigDecimal.ZERO,
                    1,
                    ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static InvoiceExtraction emptyExtraction() {
    return new InvoiceExtraction(
        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null);
  }
}
