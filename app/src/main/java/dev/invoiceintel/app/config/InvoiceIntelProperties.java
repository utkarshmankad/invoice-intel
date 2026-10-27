package dev.invoiceintel.app.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.net.URI;
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
public record InvoiceIntelProperties(@Valid @NotNull Storage storage, @Valid @NotNull Text text) {

  /** Object storage (architecture.md §9.1). Access keys stay in the environment only. */
  public record Storage(
      @NotNull URI endpoint, @NotBlank String bucketRaw, @NotBlank String bucketRedacted) {}

  /**
   * Text stage (TXT-2). {@code maxConcurrentPageJobs} caps concurrent render/OCR work to keep the
   * heap inside the 2 GB budget (architecture.md §10.1).
   */
  public record Text(
      @Min(72) @Max(600) int dpi,
      @NotEmpty List<@NotBlank String> ocrLanguages,
      @Positive int maxConcurrentPageJobs) {

    public Text {
      ocrLanguages = List.copyOf(ocrLanguages);
    }
  }
}
