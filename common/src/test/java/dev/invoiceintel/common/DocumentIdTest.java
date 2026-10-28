package dev.invoiceintel.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentIdTest {

  @Test
  void random_twice_givesDifferentIds() {
    assertThat(DocumentId.random()).isNotEqualTo(DocumentId.random());
  }

  @Test
  void toString_isTheUuid() {
    var uuid = UUID.randomUUID();

    assertThat(new DocumentId(uuid)).hasToString(uuid.toString());
  }

  @Test
  void new_nullValue_isRejected() {
    assertThatNullPointerException().isThrownBy(() -> new DocumentId(null));
  }
}
