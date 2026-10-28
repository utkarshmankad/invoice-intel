package dev.invoiceintel.app.config;

import jakarta.validation.Valid;
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
 * pipeline}, {@code pii} and {@code llm} are added with the tasks that use them.
 */
@Validated
@ConfigurationProperties("invoice-intel")
public record InvoiceIntelProperties(
    @Valid @NotNull Security security, @Valid @NotNull Storage storage, @Valid @NotNull Text text) {

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
}
