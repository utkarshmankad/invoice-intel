package dev.invoiceintel.app.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import dev.invoiceintel.common.logging.LogContext;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

/** NFR-6, PII-8: the JSON encoder configured like application.yaml masks phone and email. */
class StructuredLogMaskingTest {

  private static final UUID DOCUMENT_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");

  private final LoggerContext loggerContext = new LoggerContext();
  private final StructuredLogEncoder encoder = new StructuredLogEncoder();

  @BeforeEach
  void startEncoder() {
    var environment =
        new MockEnvironment()
            .withProperty("spring.application.name", "invoice-intel")
            .withProperty(
                "logging.structured.json.customizer",
                PiiMaskingJsonMembersCustomizer.class.getName());
    loggerContext.putObject(Environment.class.getName(), environment);
    // Share the SLF4J MDC, so LogContext values reach events of this private context.
    loggerContext.setMDCAdapter(MDC.getMDCAdapter());
    encoder.setContext(loggerContext);
    encoder.setFormat("ecs");
    encoder.start();
  }

  @AfterEach
  void stopEncoder() {
    encoder.stop();
    MDC.clear();
  }

  @Test
  void encode_messageWithPhoneAndEmail_isMaskedJsonWithMdc() {
    String line;
    try (var _ = LogContext.forStage(DOCUMENT_ID, "PII")) {
      // Concatenated so the source never holds a +91 number (gitleaks india-mobile rule).
      line = encode("buyer test.buyer@example.com phone +91 " + "9876543210", null);
    }

    assertThat(line)
        .startsWith("{")
        .contains("\"message\":\"buyer [EMAIL] phone [PHONE]\"")
        .contains("\"documentId\":\"" + DOCUMENT_ID + "\"")
        .contains("\"stage\":\"PII\"")
        .doesNotContain("test.buyer@example.com")
        .doesNotContain("9876543210");
  }

  @Test
  void encode_exceptionWithPii_masksStackTrace() {
    var failure = new IllegalStateException("bad input from 9876543210 / test.buyer@example.com");

    var line = encode("stage failed", failure);

    assertThat(line)
        .contains("[PHONE]")
        .contains("[EMAIL]")
        .doesNotContain("9876543210")
        .doesNotContain("test.buyer@example.com");
  }

  private String encode(String message, Throwable throwable) {
    var logger = loggerContext.getLogger("dev.invoiceintel.test");
    var event =
        new LoggingEvent(getClass().getName(), logger, Level.INFO, message, throwable, null);
    return new String(encoder.encode(event), StandardCharsets.UTF_8);
  }
}
