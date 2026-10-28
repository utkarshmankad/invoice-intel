package dev.invoiceintel.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UnsupportedFileTypeExceptionTest {

  @Test
  void new_keepsIndexAndTypeOnly() {
    var e = new UnsupportedFileTypeException(2, "text/plain");

    assertThat(e.fileIndex()).isEqualTo(2);
    assertThat(e.detectedType()).isEqualTo("text/plain");
    assertThat(e).hasMessage("file 2 has unsupported type text/plain");
  }
}
