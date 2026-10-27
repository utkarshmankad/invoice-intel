package dev.invoiceintel.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.invoiceintel.common.logging.LogContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** NFR-6, PII-8: the running app writes JSON log lines with MDC fields and masked PII. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestAdminConfiguration.class})
@ExtendWith(OutputCaptureExtension.class)
class StructuredLoggingIT {

  private static final Logger log = LoggerFactory.getLogger(StructuredLoggingIT.class);

  @Test
  void log_withPhoneAndEmail_writesMaskedJson(CapturedOutput output) {
    var documentId = UUID.fromString("00000000-0000-4000-8000-000000000002");

    try (var _ = LogContext.forStage(documentId, "TEXT")) {
      log.info("mistake: logged test.buyer@example.com and 9876543210");
    }

    var line =
        output
            .getOut()
            .lines()
            .filter(l -> l.contains("mistake: logged"))
            .findFirst()
            .orElseThrow();
    assertThat(line)
        .startsWith("{")
        .contains("\"message\":\"mistake: logged [EMAIL] and [PHONE]\"")
        .contains("\"documentId\":\"" + documentId + "\"")
        .contains("\"stage\":\"TEXT\"");
    assertThat(output.getAll()).doesNotContain("test.buyer@example.com", "9876543210");
  }
}
