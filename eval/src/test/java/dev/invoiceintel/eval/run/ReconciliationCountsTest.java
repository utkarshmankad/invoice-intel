package dev.invoiceintel.eval.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReconciliationCountsTest {

  @TempDir Path dir;

  static ReconciliationCounts sample() {
    Map<String, Integer> orders = new LinkedHashMap<>();
    orders.put("MATCHED", 3);
    orders.put("AMOUNT_MISMATCH", 1);
    orders.put("ORDER_WITHOUT_INVOICE", 2);
    return new ReconciliationCounts(4, 3, orders);
  }

  @Test
  void writeThenRead_roundTrips() {
    sample().write(dir);

    assertThat(ReconciliationCounts.read(dir)).contains(sample());
  }

  @Test
  void read_noFile_isEmpty() {
    assertThat(ReconciliationCounts.read(dir)).isEmpty();
  }

  @Test
  void read_appBatchShape_parses() throws IOException {
    Files.writeString(
        dir.resolve(ReconciliationCounts.FILE),
        """
        {"documents": 2, "matchedDocuments": 1, "orders": {"MATCHED": 1, "DATE_MISMATCH": 1}}
        """);

    var counts = ReconciliationCounts.read(dir).orElseThrow();

    assertThat(counts.matchRate()).hasValue(0.5);
    assertThat(counts.orders())
        .containsExactly(Map.entry("MATCHED", 1), Map.entry("DATE_MISMATCH", 1));
  }

  @Test
  void read_missingKeyOrNonIntegerCount_throws() throws IOException {
    Path file = dir.resolve(ReconciliationCounts.FILE);
    Files.writeString(file, "{\"documents\": 2, \"orders\": {}}");
    assertThatThrownBy(() -> ReconciliationCounts.read(dir))
        .isInstanceOf(IllegalArgumentException.class);

    Files.writeString(
        file, "{\"documents\": 2, \"matchedDocuments\": 1, \"orders\": {\"MATCHED\": \"x\"}}");
    assertThatThrownBy(() -> ReconciliationCounts.read(dir))
        .isInstanceOf(IllegalArgumentException.class);

    Files.writeString(file, "not json");
    assertThatThrownBy(() -> ReconciliationCounts.read(dir))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void new_moreMatchedThanDocuments_throws() {
    assertThatThrownBy(() -> new ReconciliationCounts(1, 2, Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ReconciliationCounts(-1, 0, Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void matchRate_noDocuments_isEmpty() {
    assertThat(new ReconciliationCounts(0, 0, Map.of()).matchRate()).isEmpty();
  }
}
