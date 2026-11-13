package dev.invoiceintel.pipeline;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.common.extraction.Field;
import dev.invoiceintel.common.extraction.InvoiceExtraction;
import dev.invoiceintel.extract.ExtractionStore;
import dev.invoiceintel.pipeline.internal.FieldCorrectionRepository;
import dev.invoiceintel.reconcile.InvoiceSource;
import dev.invoiceintel.reconcile.ReconciliationInvoice;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The invoices a reconciliation run reads (REC-2, architecture.md §6.7, ADR-26): every document the
 * pipeline finished with a decision ({@code AUTO_APPROVED}, {@code NEEDS_REVIEW}, {@code REVIEWED}
 * or {@code RECONCILED}), each with the extraction of its latest succeeded Extract run. Values stay
 * pseudonymized; the order number is the PII stage's token. Runs in the caller's transaction; never
 * reads {@code text_layer}, {@code pii_span}, the vault or object storage.
 */
public final class ReconciliationInvoiceReader implements InvoiceSource {

  /** The resting states after Validate; a document mid-reprocess or failed isn't reconciled. */
  static final List<DocumentState> DECIDED =
      List.of(
          DocumentState.AUTO_APPROVED,
          DocumentState.NEEDS_REVIEW,
          DocumentState.REVIEWED,
          DocumentState.RECONCILED);

  private final JdbcClient jdbc;
  private final ExtractionStore extractions;
  private final FieldCorrectionRepository corrections;

  public ReconciliationInvoiceReader(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.extractions = new ExtractionStore(jdbc);
    this.corrections = new FieldCorrectionRepository(jdbc);
  }

  @Override
  public List<ReconciliationInvoice> currentInvoices() {
    var runs =
        jdbc.sql(
                """
                SELECT d.id, max(s.run_no) AS run_no
                FROM document d
                JOIN stage_run s
                  ON s.document_id = d.id AND s.stage = 'EXTRACT' AND s.status = 'SUCCEEDED'
                WHERE d.status IN (:states)
                GROUP BY d.id
                ORDER BY d.id
                """)
            .param("states", DECIDED.stream().map(Enum::name).toList())
            .query(
                (rs, row) ->
                    new CurrentRun(
                        new DocumentId(rs.getObject("id", UUID.class)), rs.getInt("run_no")))
            .list();
    var invoices = new ArrayList<ReconciliationInvoice>(runs.size());
    for (var run : runs) {
      extractions
          .find(run.document(), run.runNo())
          .map(stored -> invoice(run.document(), corrections.current(stored.id(), stored.result())))
          .ifPresent(invoices::add);
    }
    return List.copyOf(invoices);
  }

  static ReconciliationInvoice invoice(DocumentId id, InvoiceExtraction extraction) {
    return new ReconciliationInvoice(
        id,
        extraction.documentClass().value(),
        value(extraction.orderNumber()),
        value(extraction.invoiceDate()),
        value(extraction.grandTotal()));
  }

  private static <T> T value(Field<T> field) {
    return field == null ? null : field.value();
  }

  private record CurrentRun(DocumentId document, int runNo) {}
}
