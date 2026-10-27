package dev.invoiceintel.common.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class LogContextTest {

  private static final UUID DOCUMENT_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  void forDocument_insideBlock_setsDocumentIdOnly() {
    try (var _ = LogContext.forDocument(DOCUMENT_ID)) {
      assertThat(MDC.get(LogContext.DOCUMENT_ID)).isEqualTo(DOCUMENT_ID.toString());
      assertThat(MDC.get(LogContext.STAGE)).isNull();
    }
    assertThat(MDC.get(LogContext.DOCUMENT_ID)).isNull();
  }

  @Test
  void forStage_afterClose_removesDocumentIdAndStage() {
    try (var _ = LogContext.forStage(DOCUMENT_ID, "PII")) {
      assertThat(MDC.get(LogContext.DOCUMENT_ID)).isEqualTo(DOCUMENT_ID.toString());
      assertThat(MDC.get(LogContext.STAGE)).isEqualTo("PII");
    }
    assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
  }

  @Test
  void forStageRun_insideBlock_setsRunNo() {
    try (var _ = LogContext.forStageRun(DOCUMENT_ID, "TEXT", 2)) {
      assertThat(MDC.get(LogContext.RUN_NO)).isEqualTo("2");
    }
    assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
  }

  @Test
  void close_otherMdcKeys_areKept() {
    MDC.put("traceId", "abc");
    try (var _ = LogContext.forStage(DOCUMENT_ID, "PII")) {
      assertThat(MDC.get("traceId")).isEqualTo("abc");
    }
    assertThat(MDC.get("traceId")).isEqualTo("abc");
  }

  @Test
  void forStage_nullArguments_areRejected() {
    assertThatNullPointerException().isThrownBy(() -> LogContext.forDocument(null));
    assertThatNullPointerException().isThrownBy(() -> LogContext.forStage(DOCUMENT_ID, null));
    assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
  }
}
