package dev.invoiceintel.pii;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.text.TextLayer;
import dev.invoiceintel.text.TextPage;
import dev.invoiceintel.text.TextSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** NerDetector with the chosen model (ADR-15) on synthetic text; no real invoice content. */
class NerDetectorTest {

  private static final DocumentContext CTX = new DocumentContext(DocumentId.random());

  private static NerDetector detector;
  private static String modelName;
  private static Path modelDir;

  @BeforeAll
  static void loadModel() throws IOException {
    Map.Entry<String, Path> chosen = NerModels.chosen();
    modelName = chosen.getKey();
    modelDir = chosen.getValue();
    detector = NerDetector.load(modelName, modelDir);
  }

  @AfterAll
  static void closeModel() {
    detector.close();
  }

  private static TextLayer layer(String... pages) {
    var list = new ArrayList<TextPage>();
    for (int i = 0; i < pages.length; i++) {
      list.add(
          new TextPage(
              i + 1,
              595,
              842,
              TextSource.PDF_TEXT,
              new SyntheticNerSamples.Sample(pages[i], List.of())
                  .layer()
                  .pages()
                  .getFirst()
                  .words()));
    }
    return new TextLayer(list);
  }

  private static List<String> covered(String text, List<PiiSpan> spans, PiiType type) {
    return spans.stream()
        .filter(span -> span.type() == type)
        .map(span -> text.substring(span.start(), span.end()))
        .toList();
  }

  @Test
  void detect_nameAfterLabel_reportsPersonOnWholeName() {
    String text = "Customer Name: Priya Sharma";

    List<PiiSpan> spans = detector.detect(layer(text), CTX);

    assertThat(covered(text, spans, PiiType.PERSON)).containsExactly("Priya Sharma");
    assertThat(spans).allSatisfy(span -> assertThat(span.detector()).isEqualTo(detector.name()));
    assertThat(detector.name()).isEqualTo("ner:" + modelName);
  }

  @Test
  void detect_upperCaseName_reportsPerson() {
    String text = "Order placed by ROHAN IYER on 12.03.2025";

    List<PiiSpan> spans = detector.detect(layer(text), CTX);

    assertThat(covered(text, spans, PiiType.PERSON)).containsExactly("ROHAN IYER");
  }

  @Test
  void detect_productDescription_reportsNothing() {
    String text = "Stainless Steel Pressure Cooker 5 Litre with Induction Base";

    assertThat(detector.detect(layer(text), CTX)).isEmpty();
  }

  @Test
  void detect_stateNames_areNeverCovered() {
    String text = "Delivered from Chennai, Tamil Nadu to Kochi in Kerala";

    List<PiiSpan> spans = detector.detect(layer(text), CTX);

    assertThat(spans)
        .allSatisfy(
            span ->
                assertThat(text.substring(span.start(), span.end()))
                    .doesNotContain("Tamil", "Nadu", "Kerala"));
  }

  @Test
  void detect_pageLongerThanModelLimit_findsNameAtTheEnd() {
    var text = new StringBuilder();
    while (text.length() < 4000) {
      text.append("Wireless Bluetooth Earphones with Mic, Black 1,299.00 ");
    }
    text.append("Received by Arjun Mehta at the doorstep");
    String page = text.toString();

    List<PiiSpan> spans = detector.detect(layer(page), CTX);

    assertThat(covered(page, spans, PiiType.PERSON)).contains("Arjun Mehta");
  }

  @Test
  void detect_smallWindow_coversWholePage() throws IOException {
    String page =
        "Customer Name: Priya Sharma Wireless Bluetooth Earphones with Mic, Black 1,299.00"
            + " Basmati Rice Premium 5 kg Pack Received by Arjun Mehta at the doorstep";
    try (NerDetector small = NerDetector.load(modelName, modelDir, 0.5, 12, 4)) {
      List<PiiSpan> spans = small.detect(layer(page), CTX);

      assertThat(covered(page, spans, PiiType.PERSON)).contains("Priya Sharma", "Arjun Mehta");
    }
  }

  @Test
  void detect_severalPages_numbersSpansByPage() {
    TextLayer layer =
        layer("Basmati Rice Premium 5 kg Pack", "Dear Kavya Pillai, thank you for your order.");

    List<PiiSpan> spans = detector.detect(layer, CTX);

    assertThat(spans).isNotEmpty().allSatisfy(span -> assertThat(span.pageNo()).isEqualTo(2));
  }

  @Test
  void detect_pageWithoutWords_reportsNothing() {
    var layer = new TextLayer(List.of(new TextPage(1, 595, 842, TextSource.OCR, List.of())));

    assertThat(detector.detect(layer, CTX)).isEmpty();
  }

  @Test
  void detect_nullContext_throws() {
    assertThatThrownBy(() -> detector.detect(layer("Ship To: Neha Gupta"), null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void load_missingDirectory_throwsIoException(@TempDir Path dir) {
    assertThatThrownBy(() -> NerDetector.load("missing", dir.resolve("none")))
        .isInstanceOf(IOException.class);
  }

  @Test
  void load_directoryWithoutModelFile_throwsIoException(@TempDir Path dir) throws IOException {
    Files.copy(modelDir.resolve("config.json"), dir.resolve("config.json"));
    Files.copy(modelDir.resolve("tokenizer.json"), dir.resolve("tokenizer.json"));

    assertThatThrownBy(() -> NerDetector.load("broken", dir))
        .isInstanceOf(IOException.class)
        .hasMessage("cannot load NER model broken");
  }

  @ParameterizedTest
  @CsvSource({"0.0", "-0.1", "1.5", "NaN"})
  void load_thresholdOutOfRange_throws(double threshold) {
    assertThatThrownBy(() -> NerDetector.load(modelName, modelDir, threshold))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @CsvSource({"16, 16", "16, -1", "600, 10"})
  void load_badWindow_throws(int window, int overlap) {
    assertThatThrownBy(() -> NerDetector.load(modelName, modelDir, 0.5, window, overlap))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void labelTypes_configWithoutLabels_throws(@TempDir Path dir) throws IOException {
    Path config = Files.writeString(dir.resolve("config.json"), "{\"model_type\":\"bert\"}");

    assertThatThrownBy(() -> NerDetector.labelTypes(config)).isInstanceOf(IOException.class);
  }

  @Test
  void labelTypes_indexOutOfRange_throws(@TempDir Path dir) throws IOException {
    Path config =
        Files.writeString(
            dir.resolve("config.json"), "{\"id2label\":{\"0\":\"O\",\"5\":\"B-PER\"}}");

    assertThatThrownBy(() -> NerDetector.labelTypes(config)).isInstanceOf(IOException.class);
  }

  @Test
  void labelTypes_mapsEntities(@TempDir Path dir) throws IOException {
    Path config =
        Files.writeString(
            dir.resolve("config.json"),
            "{\"id2label\":{\"0\":\"O\",\"1\":\"B-PER\",\"2\":\"I-LOC\",\"3\":\"B-ORG\"}}");

    assertThat(NerDetector.labelTypes(config))
        .containsExactly(null, PiiType.PERSON, PiiType.ADDRESS, null);
  }

  @ParameterizedTest
  @CsvSource({
    "B-PER, PERSON",
    "I-PER, PERSON",
    "PERSON, PERSON",
    "B-LOC, ADDRESS",
    "I-LOC, ADDRESS",
    "LOCATION, ADDRESS",
    "B-ORG, ",
    "B-MISC, ",
    "B-DATE, ",
    "O, "
  })
  void typeOf_label_mapsToPiiType(String label, PiiType expected) {
    assertThat(NerDetector.typeOf(label)).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "PRIYA SHARMA|Priya Sharma",
        "Ship To: ROHAN IYER|Ship To: Rohan Iyer",
        "D'SOUZA-NAIR|D'Souza-Nair",
        "GSTIN 29AB|Gstin 29Ab",
        "A B McDonald|A B McDonald",
        "Already Mixed|Already Mixed"
      })
  void titleCaseUpperWords_keepsOffsets(String text, String expected) {
    String result = NerDetector.titleCaseUpperWords(text);

    assertThat(result).isEqualTo(expected).hasSameSizeAs(text);
  }

  @Test
  void softmax_logits_sumToOne() {
    float[] probs = NerDetector.softmax(new float[] {2f, 1f, 0.1f, -3f});

    double sum = 0;
    for (float p : probs) {
      sum += p;
    }
    assertThat(sum).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
    assertThat(probs[0]).isGreaterThan(probs[1]);
    assertThat(probs[1]).isGreaterThan(probs[2]);
  }

  @Test
  void spans_joinsSameTypeWordsAndSkipsStateWords() {
    PageText text = PageText.of(layer("Neha Gupta Pune Goa Mumbai").pages().getFirst());
    PiiType[] types = {
      PiiType.PERSON, PiiType.PERSON, PiiType.ADDRESS, PiiType.ADDRESS, PiiType.ADDRESS
    };

    List<PiiSpan> spans = NerDetector.spans(text, types, NerDetector.stateWords(text), "ner:test");

    assertThat(spans)
        .containsExactly(
            new PiiSpan(1, 0, 10, PiiType.PERSON, "ner:test"),
            new PiiSpan(1, 11, 15, PiiType.ADDRESS, "ner:test"),
            new PiiSpan(1, 20, 26, PiiType.ADDRESS, "ner:test"));
  }

  @Test
  void stateWords_wordWithOtherText_isNotState() {
    PageText text =
        PageText.of(layer("Bengaluru-Karnataka Karnataka, West Bengal").pages().getFirst());

    assertThat(NerDetector.stateWords(text)).containsExactly(false, true, true, true);
  }

  @Test
  void wordAt_offsetInSpace_returnsWordBefore() {
    PageText text = PageText.of(layer("ab cd ef").pages().getFirst());

    assertThat(NerDetector.wordAt(text, 0)).isZero();
    assertThat(NerDetector.wordAt(text, 2)).isZero();
    assertThat(NerDetector.wordAt(text, 3)).isEqualTo(1);
    assertThat(NerDetector.wordAt(text, 7)).isEqualTo(2);
  }

  @Test
  void toString_showsDetectorNameOnly() {
    assertThat(detector).hasToString("NerDetector[ner:" + modelName + "]");
  }
}
