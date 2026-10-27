package dev.invoiceintel.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class InvoiceIntelPropertiesTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(PropertiesConfig.class)
          .withPropertyValues(
              "invoice-intel.storage.endpoint=http://127.0.0.1:9000",
              "invoice-intel.storage.bucket-raw=raw",
              "invoice-intel.storage.bucket-redacted=redacted",
              "invoice-intel.text.dpi=300",
              "invoice-intel.text.ocr-languages=eng,hin",
              "invoice-intel.text.max-concurrent-page-jobs=2");

  @Test
  void bind_validProperties_exposesTypedValues() {
    runner.run(
        context -> {
          var properties = context.getBean(InvoiceIntelProperties.class);
          assertThat(properties.storage().endpoint())
              .isEqualTo(URI.create("http://127.0.0.1:9000"));
          assertThat(properties.storage().bucketRaw()).isEqualTo("raw");
          assertThat(properties.storage().bucketRedacted()).isEqualTo("redacted");
          assertThat(properties.text().dpi()).isEqualTo(300);
          assertThat(properties.text().ocrLanguages()).containsExactly("eng", "hin");
          assertThat(properties.text().maxConcurrentPageJobs()).isEqualTo(2);
        });
  }

  @Test
  void bind_dpiOutOfRange_failsStartup() {
    runner
        .withPropertyValues("invoice-intel.text.dpi=1200")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void bind_blankBucket_failsStartup() {
    runner
        .withPropertyValues("invoice-intel.storage.bucket-raw= ")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void bind_zeroConcurrentPageJobs_failsStartup() {
    runner
        .withPropertyValues("invoice-intel.text.max-concurrent-page-jobs=0")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void bind_missingStorageGroup_failsStartup() {
    new ApplicationContextRunner()
        .withUserConfiguration(PropertiesConfig.class)
        .withPropertyValues("invoice-intel.text.dpi=300")
        .run(context -> assertThat(context).hasFailed());
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(InvoiceIntelProperties.class)
  static class PropertiesConfig {}
}
