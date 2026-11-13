package dev.invoiceintel.eval.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class ResultsDocumentRunnerTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @TempDir Path dataset;
  @TempDir Path results;

  @Test
  void run_extractedResult_replaysExtractionDecisionUsageAndLatency() throws IOException {
    LabelledDocument document = document(SyntheticDataset.TAX_INVOICE);
    writeResult(
        document.sha256(),
        Map.of(
            "status",
            "EXTRACTED",
            "decision",
            "AUTO_APPROVED",
            "error",
            "",
            "latencyMs",
            4321,
            "usage",
            usage("0.001250"),
            "extraction",
            LabelExtractions.from(document.label())));

    DocumentResult result = new ResultsDocumentRunner(results).run(document);

    assertThat(result.status()).isEqualTo(DocumentResult.Status.EXTRACTED);
    assertThat(result.decision()).contains(Decision.AUTO_APPROVED);
    assertThat(result.extraction()).contains(LabelExtractions.from(document.label()));
    assertThat(result.latencyMs()).hasValue(4321);
    assertThat(result.usage().costUsd()).isEqualByComparingTo(new BigDecimal("0.00125"));
    assertThat(result.usage().modelIds()).containsExactly("stub-model");
    assertThat(result.usage().promptVersions()).containsExactly("invoice-extraction/v1");
  }

  @Test
  void run_failedResult_replaysStageAndCode() throws IOException {
    LabelledDocument document = document(SyntheticDataset.TAX_INVOICE);
    Map<String, Object> failed = new LinkedHashMap<>();
    failed.put("status", "FAILED");
    failed.put("decision", "FAILED");
    failed.put("error", "EXTRACT LLM_INVALID_OUTPUT");
    failed.put("latencyMs", 12);
    failed.put("usage", usage("0.000100"));
    failed.put("extraction", null);
    writeResult(document.sha256(), failed);

    DocumentResult result = new ResultsDocumentRunner(results).run(document);

    assertThat(result.status()).isEqualTo(DocumentResult.Status.FAILED);
    assertThat(result.decision()).contains(Decision.FAILED);
    assertThat(result.error()).isEqualTo("EXTRACT LLM_INVALID_OUTPUT");
    assertThat(result.latencyMs()).hasValue(12);
  }

  @Test
  void run_noResultFile_isSkipped() {
    DocumentResult result =
        new ResultsDocumentRunner(results).run(document(SyntheticDataset.TAX_INVOICE));

    assertThat(result.status()).isEqualTo(DocumentResult.Status.SKIPPED);
    assertThat(result.latencyMs()).isEmpty();
  }

  @Test
  void run_unknownStatus_isRejected() throws IOException {
    LabelledDocument document = document(SyntheticDataset.TAX_INVOICE);
    writeResult(document.sha256(), Map.of("status", "MAYBE"));

    assertThatThrownBy(() -> new ResultsDocumentRunner(results).run(document))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void run_extractedWithoutExtraction_isRejected() throws IOException {
    LabelledDocument document = document(SyntheticDataset.TAX_INVOICE);
    writeResult(document.sha256(), Map.of("status", "EXTRACTED", "decision", "NEEDS_REVIEW"));

    assertThatThrownBy(() -> new ResultsDocumentRunner(results).run(document))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void execute_resultsRunner_reportsPipelineLatencyAndFailureCode() throws IOException {
    List<LabelledDocument> documents = Dataset.load(SyntheticDataset.write(dataset)).documents();
    LabelledDocument extracted = documents.get(0);
    LabelledDocument failed = documents.get(1);
    writeResult(
        extracted.sha256(),
        Map.of(
            "status",
            "EXTRACTED",
            "decision",
            "NEEDS_REVIEW",
            "latencyMs",
            9000,
            "usage",
            usage("0.002"),
            "extraction",
            LabelExtractions.from(extracted.label())));
    writeResult(
        failed.sha256(),
        Map.of("status", "FAILED", "error", "VALIDATE EXTRACTION_MISSING", "latencyMs", 7));

    List<DocumentRecord> records =
        EvalRun.execute(documents, new ResultsDocumentRunner(results)).records();

    assertThat(records.get(0).latencyMs()).isEqualTo(9000);
    assertThat(records.get(0).scored()).isTrue();
    assertThat(records.get(1).latencyMs()).isEqualTo(7);
    assertThat(records.get(1).error()).isEqualTo("VALIDATE EXTRACTION_MISSING");
  }

  @Test
  void failed_errorWithText_isRejected() {
    assertThatThrownBy(() -> DocumentResult.failed(LlmUsage.NONE, "EXTRACT {\"buyer\": x}"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void withLatencyMs_negative_isRejected() {
    assertThatThrownBy(() -> DocumentResult.failed().withLatencyMs(-1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void name_isResults() {
    assertThat(new ResultsDocumentRunner(results).name()).isEqualTo(ResultsDocumentRunner.NAME);
  }

  private LabelledDocument document(String sha256) {
    return Dataset.load(SyntheticDataset.write(dataset)).documents().stream()
        .filter(d -> d.sha256().equals(sha256))
        .findFirst()
        .orElseThrow();
  }

  private static Map<String, Object> usage(String cost) {
    return Map.of(
        "costUsd",
        cost,
        "modelIds",
        List.of("stub-model"),
        "promptVersions",
        List.of("invoice-extraction/v1"));
  }

  private void writeResult(String sha256, Map<String, Object> result) throws IOException {
    Files.writeString(results.resolve(sha256 + ".json"), JSON.writeValueAsString(result));
  }
}
