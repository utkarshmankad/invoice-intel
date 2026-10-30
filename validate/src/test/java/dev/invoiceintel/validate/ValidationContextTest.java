package dev.invoiceintel.validate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.invoiceintel.common.DocumentClass;
import dev.invoiceintel.common.DocumentId;
import org.junit.jupiter.api.Test;

class ValidationContextTest {

  @Test
  void nullDocumentId_isRejected() {
    assertThatThrownBy(() -> new ValidationContext(null, DocumentClass.OTHER))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void nullClassifierClass_isRejected() {
    assertThatThrownBy(() -> new ValidationContext(DocumentId.random(), null))
        .isInstanceOf(NullPointerException.class);
  }
}
