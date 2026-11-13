package dev.invoiceintel.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.invoiceintel.common.DocumentClass;
import dev.invoiceintel.common.DocumentId;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Synthetic reconciliation scenarios (REC-2, REC-4, architecture.md §6.7). */
class ReconciliationMatcherTest {

  private static final LocalDate ORDERED = LocalDate.of(2025, 3, 1);
  private final ReconciliationMatcher matcher =
      new ReconciliationMatcher(ReconciliationTolerance.DEFAULT);

  private static DocumentId id(int n) {
    return new DocumentId(new UUID(0, n));
  }

  private static ReconciliationInvoice invoice(int n, String token, String total, LocalDate date) {
    return new ReconciliationInvoice(
        id(n),
        DocumentClass.TAX_INVOICE,
        token,
        date,
        total == null ? null : new BigDecimal(total));
  }

  private static ReconciliationInvoice creditNote(int n, String token, String total) {
    return new ReconciliationInvoice(
        id(n), DocumentClass.CREDIT_NOTE, token, ORDERED.plusDays(90), new BigDecimal(total));
  }

  private static OrderHistoryItem row(String token, String total) {
    return new OrderHistoryItem(
        token, ORDERED, "Synthetic item", "B0SYNTH000", 1, null, new BigDecimal(total));
  }

  private ReconciliationMatch single(
      List<ReconciliationInvoice> invoices, List<OrderHistoryItem> rows) {
    var matches = matcher.match(invoices, rows);
    assertThat(matches).hasSize(1);
    return matches.getFirst();
  }

  @Test
  void match_oneInvoiceOneRowSameAmountAndDate_isMatched() {
    var m =
        single(List.of(invoice(1, "T1", "499.00", ORDERED.plusDays(2))), List.of(row("T1", "499")));

    assertThat(m.orderToken()).isEqualTo("T1");
    assertThat(m.status()).isEqualTo(MatchStatus.MATCHED);
    assertThat(m.invoiceIds()).containsExactly(id(1));
    assertThat(m.amountDiff()).isEqualByComparingTo("0.00");
  }

  @Test
  void match_multiSellerOrder_sumsInvoicesAgainstSumOfRows() {
    var m =
        single(
            List.of(invoice(2, "T1", "1500.00", ORDERED), invoice(1, "T1", "500.50", ORDERED)),
            List.of(row("T1", "1500.00"), row("T1", "500.00")));

    assertThat(m.status()).isEqualTo(MatchStatus.MATCHED);
    assertThat(m.invoiceIds()).containsExactly(id(1), id(2));
    assertThat(m.amountDiff()).isEqualByComparingTo("0.50");
  }

  @Test
  void match_amountDiffAtToleranceMatches_aboveIsMismatch() {
    assertThat(
            single(List.of(invoice(1, "T1", "101.00", ORDERED)), List.of(row("T1", "100")))
                .status())
        .isEqualTo(MatchStatus.MATCHED);

    var m = single(List.of(invoice(1, "T1", "98.99", ORDERED)), List.of(row("T1", "100")));
    assertThat(m.status()).isEqualTo(MatchStatus.AMOUNT_MISMATCH);
    assertThat(m.amountDiff()).isEqualByComparingTo("-1.01");
  }

  @Test
  void match_creditNoteNegativeOrPositive_isSubtracted() {
    var rows = List.of(row("T1", "700"), row("T1", "300"));
    var negative =
        single(List.of(invoice(1, "T1", "1000", ORDERED), creditNote(2, "T1", "-300")), rows);
    var positive =
        single(List.of(invoice(1, "T1", "1000", ORDERED), creditNote(2, "T1", "300")), rows);

    assertThat(negative.status()).isEqualTo(MatchStatus.AMOUNT_MISMATCH);
    assertThat(negative.amountDiff()).isEqualByComparingTo("-300.00");
    assertThat(positive.amountDiff()).isEqualByComparingTo("-300.00");
    assertThat(negative.invoiceIds()).containsExactly(id(1), id(2));
  }

  @Test
  void match_returnedItemWithCreditNote_matchesRemainingOrderTotal() {
    // The export lists only the kept item; the invoice billed both and the credit note refunds one.
    var m =
        single(
            List.of(invoice(1, "T1", "1000", ORDERED), creditNote(2, "T1", "-300")),
            List.of(row("T1", "700")));

    assertThat(m.status()).isEqualTo(MatchStatus.MATCHED);
  }

  @Test
  void match_creditNoteLongAfterOrder_isNotDateChecked() {
    var m =
        single(
            List.of(invoice(1, "T1", "500", ORDERED), creditNote(2, "T1", "-0")),
            List.of(row("T1", "500")));

    assertThat(m.status()).isEqualTo(MatchStatus.MATCHED);
  }

  @Test
  void match_invoiceDateBeyondTolerance_isDateMismatch() {
    assertThat(
            single(
                    List.of(invoice(1, "T1", "500", ORDERED.plusDays(30))),
                    List.of(row("T1", "500")))
                .status())
        .isEqualTo(MatchStatus.MATCHED);
    assertThat(
            single(
                    List.of(invoice(1, "T1", "500", ORDERED.minusDays(31))),
                    List.of(row("T1", "500")))
                .status())
        .isEqualTo(MatchStatus.DATE_MISMATCH);
  }

  @Test
  void match_dateCheckedAgainstEarliestOrderDate_andSkippedWhenMissing() {
    var late =
        new OrderHistoryItem("T1", ORDERED.plusDays(60), null, null, 1, null, BigDecimal.TEN);
    var early = new OrderHistoryItem("T1", ORDERED, null, null, 1, null, BigDecimal.TEN);
    var undated = new OrderHistoryItem("T2", null, null, null, 1, null, BigDecimal.TEN);

    var matches =
        matcher.match(
            List.of(invoice(1, "T1", "20", ORDERED.plusDays(60)), invoice(2, "T2", "10", null)),
            List.of(late, early, undated));

    assertThat(matches)
        .extracting(ReconciliationMatch::status)
        .containsExactly(MatchStatus.DATE_MISMATCH, MatchStatus.MATCHED);
  }

  @Test
  void match_rowWithoutItemTotal_usesUnitPriceTimesQuantity() {
    var rows =
        List.of(
            new OrderHistoryItem("T1", ORDERED, null, null, 3, new BigDecimal("100"), null),
            new OrderHistoryItem("T1", ORDERED, null, null, null, new BigDecimal("50"), null));

    var m = single(List.of(invoice(1, "T1", "350", ORDERED)), rows);

    assertThat(m.status()).isEqualTo(MatchStatus.MATCHED);
  }

  @Test
  void match_unknownAmountOnEitherSide_isMismatchWithoutDiff() {
    var noRowAmount = new OrderHistoryItem("T1", ORDERED, null, null, 1, null, null);
    var a = single(List.of(invoice(1, "T1", "10", ORDERED)), List.of(noRowAmount));
    var b = single(List.of(invoice(1, "T1", null, ORDERED)), List.of(row("T1", "10")));

    assertThat(List.of(a, b))
        .allSatisfy(
            m -> {
              assertThat(m.status()).isEqualTo(MatchStatus.AMOUNT_MISMATCH);
              assertThat(m.amountDiff()).isNull();
            });
  }

  @Test
  void match_oneSideMissing_isWithoutOrderOrWithoutInvoice() {
    var noNumber = invoice(9, null, "10", ORDERED);
    var matches =
        matcher.match(
            List.of(noNumber, invoice(1, "T2", "10", ORDERED)),
            List.of(row("T1", "10"), row("T1", "5")));

    assertThat(matches).hasSize(3);
    assertThat(matches.get(0))
        .isEqualTo(
            new ReconciliationMatch("T1", MatchStatus.ORDER_WITHOUT_INVOICE, List.of(), null));
    assertThat(matches.get(1))
        .isEqualTo(
            new ReconciliationMatch("T2", MatchStatus.INVOICE_WITHOUT_ORDER, List.of(id(1)), null));
    assertThat(matches.get(2))
        .isEqualTo(
            new ReconciliationMatch(null, MatchStatus.INVOICE_WITHOUT_ORDER, List.of(id(9)), null));
  }

  @Test
  void match_nothing_isEmpty() {
    assertThat(matcher.match(List.of(), List.of())).isEmpty();
  }

  @Test
  void tolerance_negative_isRejected() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new ReconciliationTolerance(new BigDecimal("-0.01"), 1));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new ReconciliationTolerance(BigDecimal.ONE, -1));
  }

  @Test
  void customTolerance_isApplied() {
    var strict = new ReconciliationMatcher(new ReconciliationTolerance(BigDecimal.ZERO, 0));
    var m = strict.match(List.of(invoice(1, "T1", "100.01", ORDERED)), List.of(row("T1", "100")));

    assertThat(m.getFirst().status()).isEqualTo(MatchStatus.AMOUNT_MISMATCH);
  }
}
