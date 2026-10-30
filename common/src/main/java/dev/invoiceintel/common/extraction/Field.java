package dev.invoiceintel.common.extraction;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/**
 * One extracted value with its metadata (specs.md §5.3). A field the document doesn't have is a
 * {@code null} {@code Field}, so a present field always has a value.
 *
 * @param value the extracted value
 * @param confidence 0–1
 * @param source where the value came from
 * @param evidence where on the document it was read, or {@code null}
 */
public record Field<T>(
    @NotNull T value,
    @DecimalMin("0.0") @DecimalMax("1.0") double confidence,
    @NotNull FieldSource source,
    @Valid Evidence evidence) {

  /** A value returned by the LLM, without evidence. */
  public static <T> Field<T> llm(T value, double confidence) {
    return new Field<>(value, confidence, FieldSource.LLM, null);
  }
}
