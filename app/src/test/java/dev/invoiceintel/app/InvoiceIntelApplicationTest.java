package dev.invoiceintel.app;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

class InvoiceIntelApplicationTest {

  @Test
  void main_withoutWebServer_startsAndReturns() {
    assertThatCode(
            () ->
                InvoiceIntelApplication.main(
                    new String[] {"--spring.main.web-application-type=none"}))
        .doesNotThrowAnyException();
  }
}
