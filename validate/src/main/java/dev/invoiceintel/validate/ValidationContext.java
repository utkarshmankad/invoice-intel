package dev.invoiceintel.validate;

import dev.invoiceintel.common.DocumentClass;
import dev.invoiceintel.common.DocumentId;
import java.util.Objects;

/**
 * What a rule may know besides the extraction (architecture.md §6.6).
 *
 * @param classifierClass the Text stage's rule-based class hint (TXT-4); {@link
 *     DocumentClass#OTHER} when the document has none
 */
public record ValidationContext(DocumentId documentId, DocumentClass classifierClass) {

  public ValidationContext {
    Objects.requireNonNull(documentId, "documentId");
    Objects.requireNonNull(classifierClass, "classifierClass");
  }
}
