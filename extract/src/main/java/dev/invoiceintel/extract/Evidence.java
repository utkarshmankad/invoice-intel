package dev.invoiceintel.extract;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * Where on the document a field was read (specs.md §5.3, REV-2). The model cites the line markers
 * it used ({@code lineRefs}, architecture.md §6.5); evidence mapping (M3.6) fills in {@code box}
 * from the text layer's word boxes. Line markers are positions, never copied text.
 *
 * @param page 1-based page number
 * @param box region on the page, or {@code null} until mapped
 * @param lineRefs line markers cited by the model; may be empty
 */
public record Evidence(@Min(1) int page, @Valid BoundingBox box, List<@NotBlank String> lineRefs) {

  public Evidence {
    lineRefs = lineRefs == null ? List.of() : List.copyOf(lineRefs);
  }
}
