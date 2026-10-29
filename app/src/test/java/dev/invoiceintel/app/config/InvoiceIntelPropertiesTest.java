package dev.invoiceintel.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.invoiceintel.pii.RedactionMode;
import java.net.URI;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class InvoiceIntelPropertiesTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(PropertiesConfig.class)
          .withPropertyValues(
              "invoice-intel.security.admin-username=admin",
              "invoice-intel.security.admin-password=not-a-real-password",
              "invoice-intel.storage.endpoint=http://127.0.0.1:9000",
              "invoice-intel.storage.bucket-raw=raw",
              "invoice-intel.storage.bucket-redacted=redacted",
              "invoice-intel.storage.access-key=not-a-real-key",
              "invoice-intel.storage.secret-key=not-a-real-secret",
              "invoice-intel.text.dpi=300",
              "invoice-intel.text.ocr-languages=eng,hin",
              "invoice-intel.text.tessdata-path=/usr/share/tesseract-ocr/5/tessdata",
              "invoice-intel.text.min-text-chars=20",
              "invoice-intel.text.max-concurrent-page-jobs=2",
              "invoice-intel.pii.mode=pseudonymize",
              "invoice-intel.pii.detectors.rules=true",
              "invoice-intel.pii.detectors.address-block=true",
              "invoice-intel.pii.detectors.ner=true",
              "invoice-intel.pii.detectors.local-llm=false",
              "invoice-intel.pii.ner.model-dir=/models/ner",
              "invoice-intel.pii.ner.threshold=0.5",
              "invoice-intel.pii.local-llm.endpoint=http://127.0.0.1:11434");

  @Test
  void bind_validProperties_exposesTypedValues() {
    runner.run(
        context -> {
          var properties = context.getBean(InvoiceIntelProperties.class);
          assertThat(properties.security().adminUsername()).isEqualTo("admin");
          assertThat(properties.security().adminPassword()).isEqualTo("not-a-real-password");
          assertThat(properties.storage().endpoint())
              .isEqualTo(URI.create("http://127.0.0.1:9000"));
          assertThat(properties.storage().bucketRaw()).isEqualTo("raw");
          assertThat(properties.storage().bucketRedacted()).isEqualTo("redacted");
          assertThat(properties.text().dpi()).isEqualTo(300);
          assertThat(properties.text().ocrLanguages()).containsExactly("eng", "hin");
          assertThat(properties.text().maxConcurrentPageJobs()).isEqualTo(2);
          assertThat(properties.text().tessdataPath())
              .isEqualTo(Path.of("/usr/share/tesseract-ocr/5/tessdata"));
          assertThat(properties.text().minTextChars()).isEqualTo(20);
        });
  }

  @Test
  void bind_missingAdminPassword_failsStartup() {
    runner
        .withPropertyValues("invoice-intel.security.admin-password=")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void securityToString_masksPassword() {
    var security = new InvoiceIntelProperties.Security("admin", "not-a-real-password");

    assertThat(security.toString()).contains("admin").doesNotContain("not-a-real-password");
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
  void bind_missingStorageSecretKey_failsStartup() {
    runner
        .withPropertyValues("invoice-intel.storage.secret-key=")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void storageToString_masksAccessKeys() {
    var storage =
        new InvoiceIntelProperties.Storage(
            URI.create("http://127.0.0.1:9000"),
            "raw",
            "redacted",
            "not-a-real-key",
            "not-a-real-secret");

    assertThat(storage.toString())
        .contains("raw", "redacted")
        .doesNotContain("not-a-real-key", "not-a-real-secret");
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

  @Test
  void bind_zeroMinTextChars_failsStartup() {
    runner
        .withPropertyValues("invoice-intel.text.min-text-chars=0")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void bind_piiDefaults_buildSettingsWithEveryLayerButLocalLlm() {
    runner.run(
        context -> {
          var settings = context.getBean(InvoiceIntelProperties.class).pii().toSettings();
          assertThat(settings.mode()).isEqualTo(RedactionMode.PSEUDONYMIZE);
          assertThat(settings.rules()).isTrue();
          assertThat(settings.addressBlock()).isTrue();
          assertThat(settings.ner().enabled()).isTrue();
          assertThat(settings.ner().modelDir()).isEqualTo(Path.of("/models/ner"));
          assertThat(settings.ner().threshold()).isEqualTo(0.5);
          assertThat(settings.localLlm().enabled()).isFalse();
        });
  }

  @Test
  void bind_redactModeWithLayersSwitchedOff_buildsSettingsWithoutThem() {
    runner
        .withPropertyValues(
            "invoice-intel.pii.mode=REDACT",
            "invoice-intel.pii.detectors.address-block=false",
            "invoice-intel.pii.detectors.ner=false",
            "invoice-intel.pii.ner.model-dir=")
        .run(
            context -> {
              var settings = context.getBean(InvoiceIntelProperties.class).pii().toSettings();
              assertThat(settings.mode()).isEqualTo(RedactionMode.REDACT);
              assertThat(settings.rules()).isTrue();
              assertThat(settings.addressBlock()).isFalse();
              assertThat(settings.ner().enabled()).isFalse();
              assertThat(settings.ner().modelDir()).isNull();
            });
  }

  @Test
  void bind_rulesOffAndLocalLlmOn_buildsSettings() {
    runner
        .withPropertyValues(
            "invoice-intel.pii.detectors.rules=false", "invoice-intel.pii.detectors.local-llm=true")
        .run(
            context -> {
              var settings = context.getBean(InvoiceIntelProperties.class).pii().toSettings();
              assertThat(settings.rules()).isFalse();
              assertThat(settings.localLlm().enabled()).isTrue();
            });
  }

  @Test
  void toSettings_everyRealLayerOff_isRejected() {
    runner
        .withPropertyValues(
            "invoice-intel.pii.detectors.rules=false",
            "invoice-intel.pii.detectors.address-block=false",
            "invoice-intel.pii.detectors.ner=false")
        .run(
            context -> {
              var pii = context.getBean(InvoiceIntelProperties.class).pii();
              assertThatThrownBy(pii::toSettings).isInstanceOf(IllegalArgumentException.class);
            });
  }

  @Test
  void toSettings_remoteLocalLlmEndpoint_isRejected() {
    runner
        .withPropertyValues(
            "invoice-intel.pii.detectors.local-llm=true",
            "invoice-intel.pii.local-llm.endpoint=https://llm.example.com")
        .run(
            context -> {
              var pii = context.getBean(InvoiceIntelProperties.class).pii();
              assertThatThrownBy(pii::toSettings)
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("loopback");
            });
  }

  @Test
  void bind_unknownPiiMode_failsStartup() {
    runner
        .withPropertyValues("invoice-intel.pii.mode=anonymize")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void bind_nerThresholdOutOfRange_failsStartup() {
    runner
        .withPropertyValues("invoice-intel.pii.ner.threshold=0")
        .run(context -> assertThat(context).hasFailed());
  }
}
