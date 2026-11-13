package dev.invoiceintel.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import dev.invoiceintel.common.DocumentId;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReconciliationReportTest {

  private static final DocumentId A = DocumentId.random();
  private static final DocumentId B = DocumentId.random();
  private static final DocumentId C = DocumentId.random();
  private static final DocumentId D = DocumentId.random();

  @Test
  void of_rows_countsRowsPerStatusAndDocuments() {
    var report =
        ReconciliationReport.of(
            List.of(
                new ReconciliationMatch(
                    "T1", MatchStatus.MATCHED, List.of(A, B), new BigDecimal("0.00")),
                new ReconciliationMatch(
                    "T2", MatchStatus.AMOUNT_MISMATCH, List.of(C), new BigDecimal("-20.00")),
                new ReconciliationMatch("T3", MatchStatus.ORDER_WITHOUT_INVOICE, List.of(), null),
                new ReconciliationMatch(
                    null, MatchStatus.INVOICE_WITHOUT_ORDER, List.of(D), null)));

    assertThat(report.orders())
        .containsEntry(MatchStatus.MATCHED, 1)
        .containsEntry(MatchStatus.AMOUNT_MISMATCH, 1)
        .containsEntry(MatchStatus.DATE_MISMATCH, 0)
        .containsEntry(MatchStatus.INVOICE_WITHOUT_ORDER, 1)
        .containsEntry(MatchStatus.ORDER_WITHOUT_INVOICE, 1);
    assertThat(report.documents()).isEqualTo(4);
    assertThat(report.matchedDocuments()).isEqualTo(2);
    assertThat(report.matchRate()).hasValue(0.5);
    assertThat(report.rows()).hasSize(4);
  }

  @Test
  void of_noRows_hasZeroCountsAndNoMatchRate() {
    var report = ReconciliationReport.of(List.of());

    assertThat(report.orders()).hasSize(MatchStatus.values().length).containsValue(0);
    assertThat(report.documents()).isZero();
    assertThat(report.matchRate()).isEmpty();
  }

  @Test
  void of_onlyOrdersWithoutInvoice_hasNoMatchRate() {
    var report =
        ReconciliationReport.of(
            List.of(
                new ReconciliationMatch("T", MatchStatus.ORDER_WITHOUT_INVOICE, List.of(), null)));

    assertThat(report.matchRate()).isEmpty();
  }
}
