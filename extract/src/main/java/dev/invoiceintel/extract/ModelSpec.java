package dev.invoiceintel.extract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.Set;

/**
 * One entry of the model registry (EXT-3, NFR-8, architecture.md §6.5). Prices are config so they
 * can change without a release; a local model has price 0.
 *
 * @param name registry key that runs and eval flags refer to
 * @param provider provider name; the gateway (M3.3) maps it to a client
 * @param modelId the provider's model ID, stored on each {@code llm_call}
 * @param inputPricePerMillion USD per million input tokens
 * @param outputPricePerMillion USD per million output tokens
 */
public record ModelSpec(
    @NotBlank String name,
    @NotBlank String provider,
    @NotBlank String modelId,
    @NotEmpty Set<@NotNull ModelCapability> capabilities,
    @NotNull @PositiveOrZero BigDecimal inputPricePerMillion,
    @NotNull @PositiveOrZero BigDecimal outputPricePerMillion) {

  public ModelSpec {
    // A null set stays null so Bean Validation reports it.
    capabilities = capabilities == null ? null : Set.copyOf(capabilities);
  }

  public boolean supports(ModelCapability capability) {
    return capabilities != null && capabilities.contains(capability);
  }
}
