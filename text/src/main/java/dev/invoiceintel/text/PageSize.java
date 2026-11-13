package dev.invoiceintel.text;

/**
 * The size of one {@link TextPage} in its own unit (PDF points for {@code PDF_TEXT}, image pixels
 * for {@code OCR}), without its words. Word boxes and evidence boxes share this unit, so it is the
 * coordinate space of an evidence overlay (REV-2, architecture.md §6.8).
 *
 * @param pageNo 1-based page number
 */
public record PageSize(int pageNo, double width, double height) {}
