package dev.invoiceintel.extract;

import java.util.Set;

/**
 * What the model sees of a document (EXT-2, architecture.md §6.5), chosen per run. Every strategy
 * reads only {@code RedactedContent}: its redacted text and its masked page images (PII-1, PII-5).
 */
public enum ExtractionStrategy {
  /** Redacted text with {@code [p{page}:l{line}]} line markers; cheapest. */
  TEXT(Set.of(ModelCapability.TEXT), new PromptRef("invoice-extraction", 1)),
  /** Redacted page images only: scans, photos, table-heavy layouts. */
  VISION(Set.of(ModelCapability.VISION), new PromptRef("invoice-extraction-vision", 1)),
  /** Both the marked text and the page images: hard documents, eval comparison. */
  HYBRID(
      Set.of(ModelCapability.TEXT, ModelCapability.VISION),
      new PromptRef("invoice-extraction-hybrid", 1));

  private final Set<ModelCapability> requiredCapabilities;
  private final PromptRef defaultPrompt;

  ExtractionStrategy(Set<ModelCapability> requiredCapabilities, PromptRef defaultPrompt) {
    this.requiredCapabilities = requiredCapabilities;
    this.defaultPrompt = defaultPrompt;
  }

  /** Capabilities a model needs to run this strategy ({@link ModelSpec#supports}). */
  public Set<ModelCapability> requiredCapabilities() {
    // Already immutable (Set.of); copyOf returns it as is but tells SpotBugs it isn't exposed.
    return Set.copyOf(requiredCapabilities);
  }

  /** The prompt written for this strategy's input (EXT-5). */
  public PromptRef defaultPrompt() {
    return defaultPrompt;
  }

  /** Whether {@code model} accepts this strategy's input. */
  public boolean supportedBy(ModelSpec model) {
    return requiredCapabilities.stream().allMatch(model::supports);
  }

  /** Whether the model input includes the redacted page images. */
  boolean sendsImages() {
    return requiredCapabilities.contains(ModelCapability.VISION);
  }

  /** Whether the model input includes the line-marked redacted text. */
  boolean sendsText() {
    return requiredCapabilities.contains(ModelCapability.TEXT);
  }
}
