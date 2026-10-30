package dev.invoiceintel.common.extraction;

import jakarta.validation.constraints.PositiveOrZero;

/**
 * A box on a page: {@code x}, {@code y} is the top-left corner, in the same unit as the page's text
 * layer (PDF points or image pixels, architecture.md §6.2).
 */
public record BoundingBox(
    @PositiveOrZero double x,
    @PositiveOrZero double y,
    @PositiveOrZero double w,
    @PositiveOrZero double h) {}
