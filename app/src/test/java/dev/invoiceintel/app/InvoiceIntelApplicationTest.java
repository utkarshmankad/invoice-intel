package dev.invoiceintel.app;

import static org.assertj.core.api.Assertions.assertThatCode;

import dev.invoiceintel.app.config.PiiKeyEnvironment;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

class InvoiceIntelApplicationTest {

  @Test
  void main_withoutWebServerOrMigrations_startsAndReturns() {
    // The datasource and S3 client connect lazily, so no stack is needed once Flyway and JobRunr's
    // table set-up are off.
    assertThatCode(
            () ->
                InvoiceIntelApplication.main(
                    new String[] {
                      "--spring.main.web-application-type=none",
                      "--spring.flyway.enabled=false",
                      // JobRunr's SQL storage connects at start; in-memory storage doesn't.
                      "--jobrunr.background-job-server.enabled=false",
                      "--jobrunr.database.type=mem",
                      "--invoice-intel.security.admin-password=" + UUID.randomUUID(),
                      "--invoice-intel.storage.access-key=" + UUID.randomUUID(),
                      "--invoice-intel.storage.secret-key=" + UUID.randomUUID(),
                      // Throwaway keysets; the NER model dir comes from Gradle
                      // (app/build.gradle.kts).
                      "--spring.main.sources=" + Keysets.class.getName()
                    }))
        .doesNotThrowAnyException();
  }

  /** The PII keysets {@code main} would otherwise read from the process environment. */
  @TestConfiguration(proxyBeanMethods = false)
  static class Keysets {

    @Bean
    PiiKeyEnvironment piiKeyEnvironment() {
      return new PiiKeyEnvironment(TestPiiKeys.generate());
    }
  }
}
