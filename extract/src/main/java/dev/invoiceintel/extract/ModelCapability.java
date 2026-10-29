package dev.invoiceintel.extract;

/** Input a model accepts (EXT-2): the strategies pick models by capability. */
public enum ModelCapability {
  /** Redacted text with line markers ({@code TEXT}, {@code HYBRID}). */
  TEXT,
  /** Redacted page images ({@code VISION}, {@code HYBRID}). */
  VISION
}
