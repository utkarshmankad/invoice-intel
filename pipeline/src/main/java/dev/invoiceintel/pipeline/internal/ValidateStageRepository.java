package dev.invoiceintel.pipeline.internal;

import dev.invoiceintel.common.DocumentClass;
import dev.invoiceintel.common.DocumentId;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The {@code document} reads of the Validate stage. */
public final class ValidateStageRepository {

  private final JdbcClient jdbc;

  public ValidateStageRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  /** The Text stage's class hint (TXT-4), {@link DocumentClass#OTHER} when it set none. */
  public DocumentClass documentClass(DocumentId documentId) {
    return jdbc.sql("SELECT document_class FROM document WHERE id = ?")
        .param(documentId.value())
        .query(String.class)
        .optional()
        .map(DocumentClass::valueOf)
        .orElse(DocumentClass.OTHER);
  }
}
