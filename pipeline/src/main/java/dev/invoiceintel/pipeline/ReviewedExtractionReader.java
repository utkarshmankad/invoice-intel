package dev.invoiceintel.pipeline;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.extract.ExtractionStore;
import dev.invoiceintel.pipeline.internal.FieldCorrectionRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reads every {@code REVIEWED} document with its current extraction for the label export (REV-4,
 * architecture.md §6.8, ADR-31): the latest succeeded Extract run's extraction with the review
 * corrections applied, the same values the review page and API show. A reviewed document without a
 * succeeded Extract run has nothing to export and is left out. One read-only snapshot; never reads
 * {@code text_layer}, {@code pii_span}, the vault or object storage.
 */
public final class ReviewedExtractionReader {

  private final JdbcClient jdbc;
  private final ExtractionStore extractions;
  private final FieldCorrectionRepository corrections;
  private final TransactionTemplate tx;

  public ReviewedExtractionReader(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.extractions = new ExtractionStore(jdbc);
    this.corrections = new FieldCorrectionRepository(jdbc);
    var template = new TransactionTemplate(Objects.requireNonNull(transactionManager, "tx"));
    // One snapshot, so a correction committing between the reads can't mix two states.
    template.setReadOnly(true);
    template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    this.tx = template;
  }

  /** The reviewed documents, ordered by SHA-256. */
  public List<ReviewedExtraction> reviewed() {
    return Objects.requireNonNull(tx.execute(_ -> read()));
  }

  private List<ReviewedExtraction> read() {
    var runs =
        jdbc.sql(
                """
                SELECT d.id, d.sha256, max(s.run_no) AS run_no
                FROM document d
                JOIN stage_run s
                  ON s.document_id = d.id AND s.stage = 'EXTRACT' AND s.status = 'SUCCEEDED'
                WHERE d.status = :state
                GROUP BY d.id, d.sha256
                ORDER BY d.sha256
                """)
            .param("state", DocumentState.REVIEWED.name())
            .query(
                (rs, row) ->
                    new Run(
                        new DocumentId(rs.getObject("id", UUID.class)),
                        rs.getString("sha256"),
                        rs.getInt("run_no")))
            .list();
    var reviewed = new ArrayList<ReviewedExtraction>(runs.size());
    for (var run : runs) {
      extractions
          .find(run.document(), run.runNo())
          .map(
              stored ->
                  new ReviewedExtraction(
                      run.document(),
                      run.sha256(),
                      corrections.current(stored.id(), stored.result())))
          .ifPresent(reviewed::add);
    }
    return List.copyOf(reviewed);
  }

  private record Run(DocumentId document, String sha256, int runNo) {}
}
