package dev.invoiceintel.pii;

import dev.invoiceintel.text.TextLayer;
import java.util.List;

/** One detection layer (PII-2, architecture.md §6.3). Spans may overlap; SpanMerger unions them. */
public interface PiiDetector {

  /** Spans found on every page of {@code layer}, as offsets into each page's {@link PageText}. */
  List<PiiSpan> detect(TextLayer layer, DocumentContext ctx);
}
