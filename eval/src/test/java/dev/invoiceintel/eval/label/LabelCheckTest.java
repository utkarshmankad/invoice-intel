package dev.invoiceintel.eval.label;

import static dev.invoiceintel.eval.label.LabelFixtures.BILL_OF_SUPPLY;
import static dev.invoiceintel.eval.label.LabelFixtures.TAX_INVOICE;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LabelCheckTest {

  private static final String MARKER = "Unique Marker Value 4711";

  @TempDir Path dir;

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();

  @Test
  void run_validLabels_exitsZeroWithCounts() throws IOException {
    Path labels = labels(TAX_INVOICE, BILL_OF_SUPPLY);

    assertThat(run(labels, null)).isZero();
    assertThat(output()).contains("2 label files: 2 valid (0 real, 2 synthetic), 0 with errors");
  }

  @Test
  void run_invalidLabel_listsFileAndPath() throws IOException {
    Path labels = labels(TAX_INVOICE);
    String broken = LabelFixtures.text(TAX_INVOICE).replace("\"2024-03-15\"", "\"" + MARKER + "\"");
    Files.writeString(labels.resolve(TAX_INVOICE), broken);

    assertThat(run(labels, null)).isEqualTo(1);
    assertThat(output())
        .contains(
            TAX_INVOICE + ":", "$.fields.invoiceDate: must be a calendar date", "1 with errors")
        .doesNotContain(MARKER);
  }

  @Test
  void run_unparseableJson_reportsPositionOnly() throws IOException {
    Path labels = Files.createDirectories(dir.resolve("labels"));
    Files.writeString(labels.resolve("a.json"), "{\n  \"version\": 1,\n  " + MARKER + "\n}");

    assertThat(run(labels, null)).isEqualTo(1);
    assertThat(output()).contains("a.json:", "$: not valid JSON (line 3").doesNotContain(MARKER);
  }

  @Test
  void run_withRawFolder_matchesOriginalsByHashWithoutNamingThem() throws IOException {
    Path labels = labels(TAX_INVOICE, BILL_OF_SUPPLY);
    Path raw = Files.createDirectories(dir.resolve("raw/2024"));
    Files.write(raw.resolve(MARKER + "-1.pdf"), LabelFixtures.originalOf(TAX_INVOICE));
    Files.write(
        raw.resolve(MARKER + "-2.pdf"), "an unlabelled original".getBytes(StandardCharsets.UTF_8));

    assertThat(run(labels, dir.resolve("raw"))).isEqualTo(1);
    assertThat(output())
        .contains(
            BILL_OF_SUPPLY + ": no file in the raw folder has this SHA-256",
            "2 raw files, 1 without a label")
        .doesNotContain(TAX_INVOICE + ": no file", MARKER);
  }

  @Test
  void run_everyLabelHasItsOriginal_exitsZero() throws IOException {
    Path labels = labels(TAX_INVOICE, BILL_OF_SUPPLY);
    Path raw = Files.createDirectories(dir.resolve("raw"));
    Files.write(raw.resolve("one.pdf"), LabelFixtures.originalOf(TAX_INVOICE));
    Files.write(raw.resolve("two.pdf"), LabelFixtures.originalOf(BILL_OF_SUPPLY));
    Files.writeString(raw.resolve(".DS_Store"), "ignored");

    assertThat(run(labels, raw)).isZero();
    assertThat(output()).contains("2 raw files, 0 without a label");
  }

  @Test
  void run_missingFolder_exitsTwo() {
    assertThat(run(dir.resolve("nope"), null)).isEqualTo(2);
  }

  @Test
  void sha256_matchesTheFixtureKey() throws IOException {
    Path file = dir.resolve("original.pdf");
    Files.write(file, LabelFixtures.originalOf(TAX_INVOICE));

    assertThat(LabelCheck.sha256(file) + ".json").isEqualTo(TAX_INVOICE);
  }

  private Path labels(String... names) throws IOException {
    Path labels = Files.createDirectories(dir.resolve("labels"));
    for (String name : names) {
      Files.writeString(labels.resolve(name), LabelFixtures.text(name));
    }
    return labels;
  }

  private int run(Path labels, Path raw) {
    return LabelCheck.run(labels, raw, new PrintStream(out, true, StandardCharsets.UTF_8));
  }

  private String output() {
    return out.toString(StandardCharsets.UTF_8);
  }
}
