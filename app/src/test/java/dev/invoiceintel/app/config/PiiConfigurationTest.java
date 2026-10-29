package dev.invoiceintel.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.invoiceintel.app.TestPiiKeys;
import dev.invoiceintel.pii.NerDetector;
import dev.invoiceintel.pii.PiiDetectors;
import dev.invoiceintel.pii.PiiKeys;
import dev.invoiceintel.pii.PiiVault;
import dev.invoiceintel.pii.TokenGenerator;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** Start-up wiring of the PII stage: valid setups start, broken ones fail fast (M2.10a). */
class PiiConfigurationTest {

  // The `test` task passes the staged model (app/build.gradle.kts), as pii's tests get theirs.
  private static final String MODEL_DIR = System.getProperty("invoice-intel.pii.ner.model-dir");

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(PropertiesConfig.class, PiiConfiguration.class)
          .withPropertyValues(
              "invoice-intel.security.admin-username=admin",
              "invoice-intel.security.admin-password=not-a-real-password",
              "invoice-intel.storage.endpoint=http://127.0.0.1:9000",
              "invoice-intel.storage.bucket-raw=raw",
              "invoice-intel.storage.bucket-redacted=redacted",
              "invoice-intel.storage.access-key=not-a-real-key",
              "invoice-intel.storage.secret-key=not-a-real-secret",
              "invoice-intel.text.dpi=300",
              "invoice-intel.text.ocr-languages=eng",
              "invoice-intel.text.tessdata-path=/usr/share/tesseract-ocr/5/tessdata",
              "invoice-intel.text.min-text-chars=20",
              "invoice-intel.text.max-concurrent-page-jobs=2",
              "invoice-intel.pii.mode=pseudonymize",
              "invoice-intel.pii.detectors.rules=true",
              "invoice-intel.pii.detectors.address-block=true",
              "invoice-intel.pii.detectors.ner=true",
              "invoice-intel.pii.detectors.local-llm=false",
              "invoice-intel.pii.ner.model-dir=" + MODEL_DIR,
              "invoice-intel.pii.ner.threshold=0.5",
              "invoice-intel.pii.local-llm.endpoint=http://127.0.0.1:11434");

  @Test
  void start_withModelAndKeysets_loadsNerAndKeys() {
    runner
        .withBean(PiiKeyEnvironment.class, () -> new PiiKeyEnvironment(TestPiiKeys.generate()))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(PiiDetectors.class).detectors())
                  .hasAtLeastOneElementOfType(NerDetector.class);
              assertThat(context).hasSingleBean(TokenGenerator.class).hasSingleBean(PiiVault.class);
            });
  }

  @Test
  void start_nerOnWithoutModelDir_fails() {
    runner
        .withBean(PiiKeyEnvironment.class, () -> new PiiKeyEnvironment(TestPiiKeys.generate()))
        .withPropertyValues("invoice-intel.pii.ner.model-dir=")
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .rootCause()
                    .hasMessage("NER is enabled but no model directory is set"));
  }

  @Test
  void start_withoutTokenKeyset_fails() {
    var keys = new HashMap<>(TestPiiKeys.generate());
    keys.remove(PiiKeys.TOKEN_KEYSET);

    runner
        .withBean(PiiKeyEnvironment.class, () -> new PiiKeyEnvironment(keys))
        .run(
            context ->
                assertThat(context).getFailure().rootCause().hasMessage("TOKEN_KEYSET is not set"));
  }

  @Test
  void start_withoutVaultKeyset_fails() {
    var keys = new HashMap<>(TestPiiKeys.generate());
    keys.remove(PiiKeys.VAULT_KEYSET);

    runner
        .withBean(PiiKeyEnvironment.class, () -> new PiiKeyEnvironment(keys))
        .run(
            context ->
                assertThat(context).getFailure().rootCause().hasMessage("VAULT_KEYSET is not set"));
  }

  @Test
  void toString_keyEnvironment_namesVariablesOnly() {
    var keys = new PiiKeyEnvironment(Map.of(PiiKeys.TOKEN_KEYSET, "{\"secret\":1}"));

    assertThat(keys.toString()).contains(PiiKeys.TOKEN_KEYSET).doesNotContain("secret");
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(InvoiceIntelProperties.class)
  static class PropertiesConfig {}
}
