package dev.invoiceintel.pii.internal;

import dev.invoiceintel.common.DocumentId;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code pii_vault} rows (PII-3, PII-7, architecture.md §6.3, §7). Only the pii module may use this
 * table (§4.1).
 */
public final class PiiVaultRepository {

  /** One row: the encrypted value behind {@code token}. */
  public record Row(String token, String type, byte[] ciphertext) {

    public Row {
      ciphertext = ciphertext.clone();
    }

    @Override
    public byte[] ciphertext() {
      return ciphertext.clone();
    }
  }

  private final JdbcClient jdbc;

  public PiiVaultRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  /**
   * Inserts the entry unless its token is already stored. A token always stands for the same value,
   * so an existing row is left as it is.
   */
  public void insertIfAbsent(String token, String type, byte[] ciphertext) {
    jdbc.sql(
            """
            INSERT INTO pii_vault (token, type, ciphertext) VALUES (?, ?, ?)
            ON CONFLICT (token) DO NOTHING
            """)
        .params(token, type, ciphertext)
        .update();
  }

  /**
   * The entries whose tokens appear in any {@code pii_span} row of the document, over all its PII
   * runs: a leak check (PII-6) errs towards checking too many values rather than too few.
   */
  public List<Row> findByDocument(DocumentId documentId) {
    return jdbc.sql(
            """
            SELECT v.token, v.type, v.ciphertext FROM pii_vault v
            WHERE v.token IN (SELECT s.token FROM pii_span s WHERE s.document_id = ?)
            ORDER BY v.token
            """)
        .param(documentId.value())
        .query(
            (rs, rowNum) ->
                new Row(rs.getString("token"), rs.getString("type"), rs.getBytes("ciphertext")))
        .list();
  }
}
