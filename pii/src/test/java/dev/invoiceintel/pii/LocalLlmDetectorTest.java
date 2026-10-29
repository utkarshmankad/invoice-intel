package dev.invoiceintel.pii;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.common.fixtures.InvoiceType;
import dev.invoiceintel.common.fixtures.SyntheticInvoiceGenerator;
import dev.invoiceintel.text.PdfTextExtractor;
import dev.invoiceintel.text.TextLayer;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LocalLlmDetectorTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://127.0.0.1:11434",
        "http://localhost:11434/api",
        "https://LOCALHOST",
        "http://127.10.0.1:8080",
        "http://[::1]:11434"
      })
  void new_loopbackEndpoint_isAccepted(String endpoint) {
    assertThat(new LocalLlmDetector(URI.create(endpoint)).endpoint())
        .isEqualTo(URI.create(endpoint));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://api.example.com",
        "https://10.0.0.5:11434",
        "http://192.168.1.10",
        "http://localhost.example.com",
        "http://127.0.0.1.example.com",
        "ftp://127.0.0.1",
        "file:///tmp/model"
      })
  void new_remoteOrNonHttpEndpoint_isRejected(String endpoint) {
    assertThatThrownBy(() -> new LocalLlmDetector(URI.create(endpoint)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("loopback");
  }

  @Test
  void new_nullEndpoint_isRejected() {
    assertThatThrownBy(() -> new LocalLlmDetector(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not set");
  }

  @Test
  void detect_stub_reportsNoSpans() {
    TextLayer layer =
        new PdfTextExtractor()
            .extract(
                SyntheticInvoiceGenerator.toPdf(
                    SyntheticInvoiceGenerator.generate(InvoiceType.GST_TAX_INVOICE, 1L)));
    var detector = new LocalLlmDetector(URI.create("http://127.0.0.1:11434"));

    assertThat(detector.detect(layer, DocumentContext.of(DocumentId.random(), layer))).isEmpty();
  }
}
