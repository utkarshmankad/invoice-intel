package dev.invoiceintel.app.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.invoiceintel.reconcile.OrderHistoryField;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MappingFileReaderTest {

  @TempDir Path dir;

  /** The committed mapping (plan D2) holds column names only and must stay importable. */
  @Test
  void read_committedMappingFile_parsesEveryEntry() {
    var mappings = MappingFileReader.read(Path.of("../config/order-history-mapping.yml"));

    assertThat(mappings.files()).extracting(m -> m.kind()).containsExactly("RETAIL", "DIGITAL");
    assertThat(mappings.files())
        .allSatisfy(m -> assertThat(m.fields()).containsKey(OrderHistoryField.ORDER_TOKEN));
  }

  @Test
  void read_validFile_keepsNullTargetsAsUnmappedColumns() throws IOException {
    var file =
        write(
            """
            files:
              - file: a.csv
                kind: RETAIL
                columns:
                  - name: "Order ID"
                    target: order_token
                  - name: "Website"
                    target: null
            """);
    var mapping = MappingFileReader.read(file).files().getFirst();

    assertThat(mapping.file()).isEqualTo("a.csv");
    assertThat(mapping.matches(List.of("Website", "Order ID"))).isTrue();
    assertThat(mapping.fields()).isEqualTo(Map.of(OrderHistoryField.ORDER_TOKEN, "Order ID"));
  }

  @Test
  void read_unknownTarget_isRejected() throws IOException {
    var file =
        write(
            """
            files:
              - file: a.csv
                kind: RETAIL
                columns:
                  - name: "Order ID"
                    target: order_number
            """);
    assertThatThrownBy(() -> MappingFileReader.read(file))
        .isInstanceOf(MappingFileException.class)
        .hasMessageContaining("unknown target");
  }

  @Test
  void read_duplicateColumn_isRejected() throws IOException {
    var file =
        write(
            """
            files:
              - file: a.csv
                kind: RETAIL
                columns:
                  - name: "Order ID"
                    target: order_token
                  - name: "Order ID"
                    target: null
            """);
    assertThatThrownBy(() -> MappingFileReader.read(file)).isInstanceOf(MappingFileException.class);
  }

  @Test
  void read_wrongShapes_areRejected() throws IOException {
    for (var yaml :
        List.of(
            "files: nope",
            "- just a list",
            "files:\n  - kind: RETAIL\n    columns: []",
            "files:\n  - file: a.csv\n    kind: RETAIL\n    columns: []",
            "files: [\"unclosed")) {
      var file = write(yaml);
      assertThatThrownBy(() -> MappingFileReader.read(file))
          .as(yaml)
          .isInstanceOf(MappingFileException.class);
    }
  }

  @Test
  void read_missingOrUnsetFile_isRejected() {
    assertThatThrownBy(() -> MappingFileReader.read(dir.resolve("absent.yml")))
        .isInstanceOf(MappingFileException.class);
    assertThatThrownBy(() -> MappingFileReader.read(null))
        .isInstanceOf(MappingFileException.class)
        .hasMessageContaining("mapping-file");
  }

  private Path write(String yaml) throws IOException {
    var file = Files.createTempFile(dir, "mapping", ".yml");
    Files.writeString(file, yaml);
    return file;
  }
}
