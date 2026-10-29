package dev.invoiceintel.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PromptRefTest {

  @Test
  void id_isNameSlashVersion() {
    assertThat(new PromptRef("invoice-text", 3).id()).isEqualTo("invoice-text/v3");
  }

  @Test
  void new_blankNameOrVersionBelowOne_isRejected() {
    assertThatThrownBy(() -> new PromptRef(" ", 1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PromptRef("x", 0)).isInstanceOf(IllegalArgumentException.class);
  }
}
