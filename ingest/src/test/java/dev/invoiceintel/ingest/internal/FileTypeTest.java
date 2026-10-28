package dev.invoiceintel.ingest.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class FileTypeTest {

  @ParameterizedTest
  @CsvSource({"application/pdf, PDF, pdf", "image/jpeg, JPEG, jpg", "image/png, PNG, png"})
  void ofMimeType_acceptedType_mapsToTypeAndExtension(
      String mimeType, FileType expected, String extension) {
    assertThat(FileType.ofMimeType(mimeType)).contains(expected);
    assertThat(expected.mimeType()).isEqualTo(mimeType);
    assertThat(expected.extension()).isEqualTo(extension);
  }

  @ParameterizedTest
  @ValueSource(strings = {"text/plain", "image/gif", "application/octet-stream", "image/tiff"})
  void ofMimeType_otherType_isEmpty(String mimeType) {
    assertThat(FileType.ofMimeType(mimeType)).isEmpty();
  }
}
