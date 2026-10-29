package dev.invoiceintel.app.config;

import dev.invoiceintel.pii.PiiSettings;
import dev.invoiceintel.pii.RedactionMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed configuration under {@code invoice-intel.*} (architecture.md §10). Environment-specific
 * values come from environment variables (NFR-12); see {@code application.yaml}. Groups for {@code
 * pipeline} and {@code llm} are added with the tasks that use them.
 */
@Validated
@ConfigurationProperties("invoice-intel")
public record InvoiceIntelProperties(
    @Valid @NotNull Security security,
    @Valid @NotNull Storage storage,
    @Valid @NotNull Text text,
    @Valid @NotNull Pii pii) {

  /**
   * The single admin user (NFR-2, architecture.md §11); the password comes from the environment.
   */
  public record Security(@NotBlank String adminUsername, @NotBlank String adminPassword) {

    @Override
    public String toString() {
      return "Security[adminUsername=" + adminUsername + ", adminPassword=****]";
    }
  }

  /**
   * Object storage (architecture.md §9.1). The access keys come from the environment only (PII-7,
   * NFR-12) and are masked in {@link #toString()}.
   */
  public record Storage(
      @NotNull URI endpoint,
      @NotBlank String bucketRaw,
      @NotBlank String bucketRedacted,
      @NotBlank String accessKey,
      @NotBlank String secretKey) {

    @Override
    public String toString() {
      return "Storage[endpoint="
          + endpoint
          + ", bucketRaw="
          + bucketRaw
          + ", bucketRedacted="
          + bucketRedacted
          + ", accessKey=****, secretKey=****]";
    }
  }

  /**
   * Text stage (TXT-1, TXT-2). {@code tessdataPath} holds the Tesseract language files; {@code
   * minTextChars} is the text a PDF page needs to skip OCR; {@code maxConcurrentPageJobs} caps
   * concurrent render/OCR work to keep the heap inside the 2 GB budget (architecture.md §10.1).
   */
  public record Text(
      @Min(72) @Max(600) int dpi,
      @NotEmpty List<@NotBlank String> ocrLanguages,
      @NotNull Path tessdataPath,
      @Positive int minTextChars,
      @Positive int maxConcurrentPageJobs) {

    public Text {
      ocrLanguages = List.copyOf(ocrLanguages);
    }
  }

  /**
   * PII stage (PII-2, PII-3, architecture.md §6.3): redaction mode and one switch per detection
   * layer. The cross-field rules (at least one real layer on, a model directory when NER is on, a
   * loopback local LLM endpoint) live in {@link PiiSettings}, which {@link #toSettings()} builds.
   */
  public record Pii(
      @NotNull RedactionMode mode,
      @Valid @NotNull Detectors detectors,
      @Valid @NotNull Ner ner,
      @Valid @NotNull LocalLlm localLlm) {

    /** One switch per layer; {@code rules} covers the regex and checksum detectors. */
    public record Detectors(boolean rules, boolean addressBlock, boolean ner, boolean localLlm) {}

    /** NER model directory (ADR-15; required when NER is on) and entity threshold. */
    public record Ner(
        Path modelDir,
        @DecimalMin(value = "0", inclusive = false) @DecimalMax("1") double threshold) {}

    /** Local model server; only loopback addresses are accepted (PII-2(c)). */
    public record LocalLlm(URI endpoint) {}

    /**
     * The settings the PII stage runs with.
     *
     * @throws IllegalArgumentException if the switches and values don't form a valid setup
     */
    public PiiSettings toSettings() {
      return new PiiSettings(
          mode,
          detectors.rules(),
          detectors.addressBlock(),
          new PiiSettings.Ner(detectors.ner(), ner.modelDir(), ner.threshold()),
          new PiiSettings.LocalLlm(detectors.localLlm(), localLlm.endpoint()));
    }
  }
}
