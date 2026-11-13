package dev.invoiceintel.pipeline.internal;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.common.ExtractionId;
import dev.invoiceintel.common.extraction.InvoiceExtraction;
import dev.invoiceintel.extract.FieldCorrections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The {@code field_correction} table (REV-3, architecture.md §6.8, ADR-30). */
public final class FieldCorrectionRepository {

  private final JdbcClient jdbc;

  public FieldCorrectionRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  /** One stored correction; values are pseudonymized like the extraction. */
  public record Correction(String path, String newValue) {}

  /** The corrections of {@code extraction}, oldest first. */
  public List<Correction> corrections(ExtractionId extraction) {
    return jdbc.sql(
            """
            SELECT field_path, new_value FROM field_correction
            WHERE extraction_id = ? ORDER BY created_at, id
            """)
        .param(extraction.value())
        .query((rs, row) -> new Correction(rs.getString("field_path"), rs.getString("new_value")))
        .list();
  }

  /** {@code result} with the corrections of {@code extraction} applied in order. */
  public InvoiceExtraction current(ExtractionId extraction, InvoiceExtraction result) {
    InvoiceExtraction current = result;
    for (Correction c : corrections(extraction)) {
      current = FieldCorrections.apply(current, c.path(), c.newValue());
    }
    return current;
  }

  /** Records one correction. */
  public void insert(
      DocumentId document,
      ExtractionId extraction,
      String path,
      String oldValue,
      String newValue,
      String correctedBy) {
    jdbc.sql(
            """
            INSERT INTO field_correction
              (id, document_id, extraction_id, field_path, old_value, new_value, corrected_by)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """)
        .params(
            UUID.randomUUID(),
            document.value(),
            extraction.value(),
            path,
            oldValue,
            newValue,
            correctedBy)
        .update();
  }
}
