package dev.invoiceintel.eval.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatasetTest {

  private static final String MARKER = "Unique Marker Value 4711";

  @TempDir Path dir;

  @Test
  void load_syntheticFixtures_returnsValidDocumentsSortedByHash() {
    Dataset dataset = Dataset.load(SyntheticDataset.write(dir));

    assertThat(dataset.invalid()).isEmpty();
    assertThat(dataset.documents())
        .extracting(LabelledDocument::sha256)
        .containsExactlyElementsOf(SyntheticDataset.ALL);
    assertThat(dataset.documents()).allMatch(LabelledDocument::synthetic);
    assertThat(dataset.documents().getFirst().label().get("version").intValue()).isEqualTo(1);
  }

  @Test
  void load_invalidAndUnparseableLabels_reportsPathsWithoutValues() throws IOException {
    SyntheticDataset.write(dir);
    Path taxInvoice = dir.resolve(SyntheticDataset.TAX_INVOICE + ".json");
    Files.writeString(
        taxInvoice, Files.readString(taxInvoice).replace("\"2024-03-15\"", "\"" + MARKER + "\""));
    Files.writeString(dir.resolve("broken.json"), "{\n  " + MARKER + "\n}");
    Files.writeString(dir.resolve("notes.txt"), "ignored");

    Dataset dataset = Dataset.load(dir);

    assertThat(dataset.documents())
        .extracting(LabelledDocument::sha256)
        .containsExactly(SyntheticDataset.BILL_OF_SUPPLY);
    assertThat(dataset.invalid())
        .containsOnlyKeys("broken.json", SyntheticDataset.TAX_INVOICE + ".json");
    assertThat(dataset.invalid().toString())
        .contains("$: not valid JSON (line 2", "$.fields.invoiceDate")
        .doesNotContain(MARKER);
  }

  @Test
  void load_missingFolder_throws() {
    assertThatThrownBy(() -> Dataset.load(dir.resolve("nope")))
        .isInstanceOf(UncheckedIOException.class);
  }

  @Test
  void label_isACopy() {
    LabelledDocument document = Dataset.load(SyntheticDataset.write(dir)).documents().getFirst();

    ((tools.jackson.databind.node.ObjectNode) document.label()).put("version", 99);

    assertThat(document.label().get("version").intValue()).isEqualTo(1);
  }
}
