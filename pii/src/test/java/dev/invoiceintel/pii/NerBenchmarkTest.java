package dev.invoiceintel.pii;

import static org.assertj.core.api.Assertions.assertThat;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.text.Word;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * PERSON recall and precision of the NER models on synthetic Indian names (M2.8, A2, R3). The
 * default {@code test} run measures the chosen model and guards its numbers; {@code ./gradlew
 * :pii:nerBenchmark} measures every candidate (ADR-15). Results go to {@code
 * build/reports/ner-benchmark/}. Word-level: a name word counts as found when a PERSON span covers
 * it, a PERSON word is correct when it belongs to a name.
 */
class NerBenchmarkTest {

  static final int SAMPLES = 300;
  static final long SEED = 20261028L;
  static final double[] THRESHOLDS = {0.5, 0.3, 0.15};

  record Result(
      String model,
      double threshold,
      int nameWords,
      int foundNameWords,
      int personWords,
      int names,
      int foundNames,
      int upperNameWords,
      int foundUpperNameWords,
      int negativePersonWords,
      int negativeAddressWords,
      double msPerSample) {

    double recall() {
      return ratio(foundNameWords, nameWords);
    }

    double precision() {
      return ratio(foundNameWords, personWords);
    }

    String line() {
      return String.format(
          Locale.ROOT,
          "%s threshold=%.2f wordRecall=%.4f (%d/%d) nameRecall=%.4f (%d/%d) upperCaseRecall=%.4f"
              + " (%d/%d) precision=%.4f (%d/%d) negativePersonWords=%d negativeAddressWords=%d"
              + " msPerSample=%.1f",
          model,
          threshold,
          recall(),
          foundNameWords,
          nameWords,
          ratio(foundNames, names),
          foundNames,
          names,
          ratio(foundUpperNameWords, upperNameWords),
          foundUpperNameWords,
          upperNameWords,
          precision(),
          foundNameWords,
          personWords,
          negativePersonWords,
          negativeAddressWords,
          msPerSample);
    }
  }

  @Test
  void benchmark_models_reportRecallAndPrecision() throws IOException {
    Path reportDir = Path.of("build", "reports", "ner-benchmark");
    Files.createDirectories(reportDir);
    var lines = new StringBuilder();
    var detail = new StringBuilder();
    Result chosenDefault = null;
    String chosen = NerModels.chosen().getKey();
    for (Map.Entry<String, Path> model : NerModels.all().entrySet()) {
      for (double threshold : THRESHOLDS) {
        try (NerDetector detector = NerDetector.load(model.getKey(), model.getValue(), threshold)) {
          Result result = run(model.getKey(), threshold, detector, detail);
          lines.append(result.line()).append('\n');
          if (model.getKey().equals(chosen) && threshold == NerDetector.DEFAULT_THRESHOLD) {
            chosenDefault = result;
          }
        }
      }
    }
    Files.writeString(reportDir.resolve("results.txt"), lines.toString());
    Files.writeString(reportDir.resolve("contexts.txt"), detail.toString());
    System.out.print(lines);

    // Regression guard a little below the chosen model's measured numbers (architecture A2).
    assertThat(chosenDefault).isNotNull();
    assertThat(chosenDefault.recall()).isGreaterThanOrEqualTo(0.85);
    assertThat(chosenDefault.precision()).isGreaterThanOrEqualTo(0.88);
  }

  static Result run(String model, double threshold, NerDetector detector, StringBuilder detail) {
    var ctx = new DocumentContext(DocumentId.random());
    int nameWords = 0;
    int found = 0;
    int personWords = 0;
    int names = 0;
    int foundNames = 0;
    int upper = 0;
    int foundUpper = 0;
    List<SyntheticNerSamples.Sample> positives = SyntheticNerSamples.positives(SAMPLES, SEED);
    int contexts = SyntheticNerSamples.CONTEXTS.size();
    int[][] perContext = new int[contexts][3];
    long startNanos = System.nanoTime();
    for (int i = 0; i < positives.size(); i++) {
      SyntheticNerSamples.Sample sample = positives.get(i);
      PageText text = PageText.of(sample.layer().pages().getFirst());
      Set<Word> person = covered(text, detector.detect(sample.layer(), ctx), PiiType.PERSON);
      int[] range = sample.names().getFirst();
      List<Word> name = text.wordsIn(range[0], range[1]);
      boolean isUpper =
          text.text()
              .substring(range[0], range[1])
              .equals(text.text().substring(range[0], range[1]).toUpperCase(Locale.ROOT));
      int hits = (int) name.stream().filter(person::contains).count();
      nameWords += name.size();
      found += hits;
      personWords += person.size();
      names++;
      foundNames += hits == name.size() ? 1 : 0;
      perContext[i % contexts][0] += name.size();
      perContext[i % contexts][1] += hits;
      perContext[i % contexts][2] += person.size() - hits;
      if (isUpper) {
        upper += name.size();
        foundUpper += hits;
      }
    }
    int negativePerson = 0;
    int negativeAddress = 0;
    List<SyntheticNerSamples.Sample> negatives = SyntheticNerSamples.negatives();
    for (SyntheticNerSamples.Sample sample : negatives) {
      PageText text = PageText.of(sample.layer().pages().getFirst());
      List<PiiSpan> spans = detector.detect(sample.layer(), ctx);
      int p = covered(text, spans, PiiType.PERSON).size();
      negativePerson += p;
      personWords += p;
      negativeAddress += covered(text, spans, PiiType.ADDRESS).size();
    }
    for (int c = 0; c < contexts; c++) {
      detail.append(
          String.format(
              Locale.ROOT,
              "%s threshold=%.2f context=\"%s\" nameWordsFound=%d/%d otherPersonWords=%d%n",
              model,
              threshold,
              SyntheticNerSamples.CONTEXTS.get(c),
              perContext[c][1],
              perContext[c][0],
              perContext[c][2]));
    }
    double ms = (System.nanoTime() - startNanos) / 1e6 / (positives.size() + negatives.size());
    return new Result(
        model,
        threshold,
        nameWords,
        found,
        personWords,
        names,
        foundNames,
        upper,
        foundUpper,
        negativePerson,
        negativeAddress,
        ms);
  }

  private static Set<Word> covered(PageText text, List<PiiSpan> spans, PiiType type) {
    var words = new HashSet<Word>();
    spans.stream()
        .filter(span -> span.type() == type)
        .forEach(span -> words.addAll(text.wordsIn(span.start(), span.end())));
    return words;
  }

  private static double ratio(int a, int b) {
    return b == 0 ? 1.0 : (double) a / b;
  }
}
