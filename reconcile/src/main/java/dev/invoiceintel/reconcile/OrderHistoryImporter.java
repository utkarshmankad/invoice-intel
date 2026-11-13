package dev.invoiceintel.reconcile;

import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Imports one order-history CSV (REC-1, architecture.md §6.7): picks the mapping by header,
 * tokenizes order numbers and stores the rows under a new {@link ImportId} in one transaction.
 * Imports add up; each keeps its own ID.
 */
public final class OrderHistoryImporter {

  private final OrderHistoryParser parser;
  private final OrderHistoryStore store;
  private final TransactionTemplate tx;

  public OrderHistoryImporter(
      JdbcClient jdbc, PlatformTransactionManager transactionManager, OrderTokenizer tokenizer) {
    this.parser = new OrderHistoryParser(tokenizer);
    this.store = new OrderHistoryStore(jdbc);
    this.tx = new TransactionTemplate(Objects.requireNonNull(transactionManager, "tx"));
  }

  /**
   * @throws OrderHistoryImportException if the file can't be imported at all; nothing is stored
   */
  public Imported importCsv(String csv, OrderHistoryMappings mappings) {
    var parsed = parser.parse(csv, mappings);
    var importId = ImportId.random();
    tx.executeWithoutResult(_ -> store.insert(importId, parsed.items()));
    return new Imported(
        importId,
        parsed.mapping().file(),
        parsed.mapping().kind(),
        parsed.items().size(),
        parsed.skipped());
  }

  /**
   * The outcome of an import: counts and codes only (PII-8).
   *
   * @param mapping the mapping file entry used, by its {@code file} name
   */
  public record Imported(
      ImportId importId,
      String mapping,
      String kind,
      int imported,
      Map<SkipReason, Integer> skipped) {

    public Imported {
      skipped = Map.copyOf(skipped);
    }
  }
}
