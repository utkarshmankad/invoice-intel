package dev.invoiceintel.pii;

import static org.assertj.core.api.Assertions.assertThat;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.common.fixtures.InvoiceType;
import dev.invoiceintel.common.fixtures.SyntheticInvoice;
import dev.invoiceintel.common.fixtures.SyntheticInvoiceGenerator;
import dev.invoiceintel.text.PdfTextExtractor;
import dev.invoiceintel.text.TextLayer;
import java.io.IOException;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * M2.9 done-when: each detection layer can be switched off in {@link PiiSettings}, and detection
 * plus redaction work in both modes (PII-2, PII-3).
 */
class PiiDetectorsTest {

  private static final SyntheticInvoice INVOICE =
      SyntheticInvoiceGenerator.generate(InvoiceType.GST_TAX_INVOICE, 7L);
  private static final TextLayer LAYER =
      new PdfTextExtractor().extract(SyntheticInvoiceGenerator.toPdf(INVOICE));
  private static final DocumentContext CTX = DocumentContext.of(DocumentId.random(), LAYER);
  private static final PiiSettings.LocalLlm LOCAL_LLM =
      new PiiSettings.LocalLlm(true, URI.create("http://127.0.0.1:11434"));

  private static TokenGenerator tokens;
  private static PiiVault vault;

  @BeforeAll
  static void keys() throws GeneralSecurityException {
    tokens = TestKeysets.tokens(TestKeysets.newTokenKeyset());
    vault = TestKeysets.vault(TestKeysets.newVaultKeyset());
  }

  private static PiiSettings.Ner nerOn() {
    return new PiiSettings.Ner(true, NerModels.chosen().getValue(), NerDetector.DEFAULT_THRESHOLD);
  }

  private static List<Class<?>> types(PiiDetectors detectors) {
    return detectors.detectors().stream().<Class<?>>map(Object::getClass).toList();
  }

  @Test
  void create_allLayers_buildsEveryDetectorInLayerOrder() throws IOException {
    var settings = new PiiSettings(RedactionMode.PSEUDONYMIZE, true, true, nerOn(), LOCAL_LLM);

    try (var detectors = PiiDetectors.create(settings)) {
      assertThat(types(detectors))
          .containsExactly(
              RegexDetector.class,
              ChecksumDetector.class,
              AddressBlockDetector.class,
              NerDetector.class,
              LocalLlmDetector.class);
    }
  }

  @Test
  void create_rulesOff_leavesOutRegexAndChecksum() throws IOException {
    var settings = new PiiSettings(RedactionMode.PSEUDONYMIZE, false, true, nerOn(), LOCAL_LLM);

    try (var detectors = PiiDetectors.create(settings)) {
      assertThat(types(detectors))
          .containsExactly(AddressBlockDetector.class, NerDetector.class, LocalLlmDetector.class);
      assertThat(detectors.detect(LAYER, CTX))
          .isNotEmpty()
          .noneMatch(span -> span.detector().startsWith("regex:"))
          .noneMatch(span -> span.detector().startsWith("checksum:"));
    }
  }

  @Test
  void create_addressBlockOff_reportsNoAddressBlockSpans() throws IOException {
    var settings =
        new PiiSettings(
            RedactionMode.PSEUDONYMIZE,
            true,
            false,
            PiiSettings.Ner.off(),
            PiiSettings.LocalLlm.off());

    try (var detectors = PiiDetectors.create(settings)) {
      assertThat(types(detectors)).containsExactly(RegexDetector.class, ChecksumDetector.class);
      assertThat(detectors.detect(LAYER, CTX))
          .isNotEmpty()
          .allMatch(
              span ->
                  span.detector().startsWith("regex:") || span.detector().startsWith("checksum:"));
    }
  }

  @Test
  void create_nerOff_reportsNoNerSpans() throws IOException {
    var settings =
        new PiiSettings(RedactionMode.PSEUDONYMIZE, true, true, PiiSettings.Ner.off(), LOCAL_LLM);

    try (var detectors = PiiDetectors.create(settings)) {
      assertThat(types(detectors)).doesNotContain(NerDetector.class);
      assertThat(detectors.detect(LAYER, CTX))
          .isNotEmpty()
          .noneMatch(span -> span.detector().startsWith("ner:"));
    }
  }

  @Test
  void create_onlyNer_reportsOnlyNerSpans() throws IOException {
    var settings =
        new PiiSettings(
            RedactionMode.PSEUDONYMIZE, false, false, nerOn(), PiiSettings.LocalLlm.off());

    try (var detectors = PiiDetectors.create(settings)) {
      assertThat(types(detectors)).containsExactly(NerDetector.class);
      assertThat(detectors.detect(LAYER, CTX))
          .isNotEmpty()
          .allMatch(span -> span.detector().startsWith("ner:" + PiiDetectors.NER_MODEL_NAME));
    }
  }

  @Test
  void create_localLlmOff_leavesOutLocalLlmDetector() throws IOException {
    var settings =
        new PiiSettings(
            RedactionMode.PSEUDONYMIZE,
            true,
            true,
            PiiSettings.Ner.off(),
            PiiSettings.LocalLlm.off());

    try (var detectors = PiiDetectors.create(settings)) {
      assertThat(types(detectors)).doesNotContain(LocalLlmDetector.class);
    }
  }

  @ParameterizedTest
  @EnumSource(RedactionMode.class)
  void detectAndRedact_rulesAndAddressBlock_removeBuyerPiiInBothModes(RedactionMode mode)
      throws IOException {
    var settings =
        new PiiSettings(mode, true, true, PiiSettings.Ner.off(), PiiSettings.LocalLlm.off());

    try (var detectors = PiiDetectors.create(settings)) {
      List<PiiSpan> merged = new SpanMerger().merge(LAYER, detectors.detect(LAYER, CTX));
      TextRedactor.Result result =
          new TextRedactor(tokens, vault).redact(LAYER, merged, CTX, settings.mode());
      String text = result.content().text();

      assertThat(text)
          .doesNotContain(INVOICE.buyer().name(), INVOICE.buyer().email())
          .contains(tokens.token(PiiType.ORDER_NUMBER, INVOICE.orderNumber()));
      if (mode == RedactionMode.PSEUDONYMIZE) {
        assertThat(text).contains(tokens.token(PiiType.PERSON, INVOICE.buyer().name()));
        assertThat(result.vaultEntries()).isNotEmpty();
      } else {
        assertThat(text).contains("[REDACTED:PERSON]");
        assertThat(result.vaultEntries()).isEmpty();
      }
    }
  }
}
