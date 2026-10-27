package dev.invoiceintel.app;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class InvoiceIntelApplicationTest {

  @Test
  void main_withoutWebServerOrMigrations_startsAndReturns() {
    // The datasource connects lazily, so no database is needed once Flyway is off.
    assertThatCode(
            () ->
                InvoiceIntelApplication.main(
                    new String[] {
                      "--spring.main.web-application-type=none",
                      "--spring.flyway.enabled=false",
                      "--invoice-intel.security.admin-password=" + UUID.randomUUID()
                    }))
        .doesNotThrowAnyException();
  }
}
