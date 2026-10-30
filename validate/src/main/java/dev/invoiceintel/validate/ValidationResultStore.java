package dev.invoiceintel.validate;

import dev.invoiceintel.common.ExtractionId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Reads and writes the {@code validation_result} rows of an extraction (architecture.md §7, V8).
 * Validating an extraction again replaces its rows, so they always describe the latest Validate
 * run; call {@link #replace} inside the stage transaction.
 */
public final class ValidationResultStore {

  private final JdbcClient jdbc;

  public ValidationResultStore(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  public void replace(ExtractionId extractionId, List<ValidationResult> results) {
    jdbc.sql("DELETE FROM validation_result WHERE extraction_id = ?")
        .param(extractionId.value())
        .update();
    for (ValidationResult result : results) {
      jdbc.sql(
              """
              INSERT INTO validation_result (id, extraction_id, rule_id, outcome, message)
              VALUES (?, ?, ?, ?, ?)
              """)
          .params(
              UUID.randomUUID(),
              extractionId.value(),
              result.ruleId(),
              result.outcome().name(),
              result.message())
          .update();
    }
  }

  /** The extraction's results, ordered by rule ID. */
  public List<ValidationResult> find(ExtractionId extractionId) {
    return jdbc.sql(
            """
            SELECT rule_id, outcome, message FROM validation_result
            WHERE extraction_id = ? ORDER BY rule_id
            """)
        .param(extractionId.value())
        .query(
            (rs, row) ->
                new ValidationResult(
                    rs.getString("rule_id"),
                    RuleOutcome.valueOf(rs.getString("outcome")),
                    rs.getString("message")))
        .list();
  }
}
