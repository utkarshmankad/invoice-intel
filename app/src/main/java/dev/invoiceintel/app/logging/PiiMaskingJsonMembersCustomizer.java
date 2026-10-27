package dev.invoiceintel.app.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/**
 * Applies {@link PiiLogMasker} to every string value of a structured JSON log line: the message,
 * MDC fields and stack traces (PII-8). Registered through {@code
 * logging.structured.json.customizer} in {@code application.yaml}.
 */
public class PiiMaskingJsonMembersCustomizer
    implements StructuredLoggingJsonMembersCustomizer<ILoggingEvent> {

  @Override
  public void customize(JsonWriter.Members<ILoggingEvent> members) {
    members.applyingValueProcessor(JsonWriter.ValueProcessor.of(String.class, PiiLogMasker::mask));
  }
}
