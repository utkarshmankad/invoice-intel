package dev.invoiceintel.pii.internal;

import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code pii_vault} rows (PII-3, PII-7, architecture.md §6.3, §7). Only the pii module may use this
 * table (§4.1).
 */
public final class PiiVaultRepository {

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
}
