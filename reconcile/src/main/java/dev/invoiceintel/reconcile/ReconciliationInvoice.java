package dev.invoiceintel.reconcile;

import dev.invoiceintel.common.DocumentClass;
import dev.invoiceintel.common.DocumentId;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * The fields of one document's current extraction the matcher needs (REC-2, REC-4, architecture.md
 * §6.7). Values are pseudonymized: {@code orderToken} is the PII stage's {@code ORDER_NUMBER}
 * token, the same one the order-history import stores. The caller reads them from the extraction,
 * since {@code reconcile} can't depend on {@code extract} (§4.1).
 *
 * @param orderToken null when the extraction has no order number
 * @param invoiceDate null when the extraction has none; the date isn't checked then
 * @param grandTotal null when the extraction has none; the amount can't be verified then
 */
public record ReconciliationInvoice(
    DocumentId documentId,
    DocumentClass documentClass,
    String orderToken,
    LocalDate invoiceDate,
    BigDecimal grandTotal) {

  public ReconciliationInvoice {
    Objects.requireNonNull(documentId, "documentId");
    Objects.requireNonNull(documentClass, "documentClass");
  }

  boolean isCreditNote() {
    return documentClass == DocumentClass.CREDIT_NOTE;
  }
}
