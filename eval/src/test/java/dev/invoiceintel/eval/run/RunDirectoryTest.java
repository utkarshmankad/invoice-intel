package dev.invoiceintel.eval.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunDirectoryTest {

  private static final RunConfig CONFIG =
      new RunConfig(
          "2026-11-03-a",
          Instant.parse("2026-11-03T10:15:30Z"),
          "data/labels",
          "TEXT",
          "claude-text-default",
          "stub");

  private static final List<DocumentRecord> RECORDS =
      List.of(
          new DocumentRecord("a".repeat(64), true, DocumentResult.Status.EXTRACTED, 1200, ""),
          new DocumentRecord(
              "b".repeat(64), false, DocumentResult.Status.FAILED, 30, "IllegalStateException"));

  @TempDir Path dir;

  @Test
  void writeThenRead_roundTrips() {
    Path run = dir.resolve("reports/2026-11-03-a");

    new RunDirectory(CONFIG, RECORDS).write(run);
    RunDirectory read = RunDirectory.read(run);

    assertThat(read.config()).isEqualTo(CONFIG);
    assertThat(read.documents()).isEqualTo(RECORDS);
  }

  @Test
  void write_recordsConfigAndCsv() throws IOException {
    Path run = dir.resolve("run");

    new RunDirectory(CONFIG, RECORDS).write(run);

    assertThat(Files.readString(run.resolve(RunDirectory.CONFIG)))
        .contains(
            "\"runId\" : \"2026-11-03-a\"",
            "\"strategy\" : \"TEXT\"",
            "\"model\" : \"claude-text-default\"",
            "\"runner\" : \"stub\"",
            "\"documents\" : 2");
    assertThat(Files.readAllLines(run.resolve(RunDirectory.DOCUMENTS)))
        .containsExactly(
            RunDirectory.CSV_HEADER,
            "a".repeat(64) + ",true,EXTRACTED,1200,",
            "b".repeat(64) + ",false,FAILED,30,IllegalStateException");
  }

  @Test
  void write_existingFolder_throws() throws IOException {
    Files.createDirectories(dir.resolve("run"));

    assertThatThrownBy(() -> new RunDirectory(CONFIG, RECORDS).write(dir.resolve("run")))
        .isInstanceOf(UncheckedIOException.class);
  }

  @Test
  void read_folderWithoutRunFiles_throws() {
    assertThatThrownBy(() -> RunDirectory.read(dir))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Not a run folder");
  }

  @Test
  void read_malformedRow_throws() throws IOException {
    Path run = dir.resolve("run");
    new RunDirectory(CONFIG, RECORDS).write(run);
    Files.writeString(run.resolve(RunDirectory.DOCUMENTS), RunDirectory.CSV_HEADER + "\nx,y\n\n");

    assertThatThrownBy(() -> RunDirectory.read(run))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Malformed row");
  }

  @Test
  void read_configWithoutKey_throws() throws IOException {
    Path run = dir.resolve("run");
    new RunDirectory(CONFIG, RECORDS).write(run);
    Files.writeString(run.resolve(RunDirectory.CONFIG), "{\"runId\": 1}");

    assertThatThrownBy(() -> RunDirectory.read(run))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("runId");
  }

  @Test
  void read_blankLines_areIgnored() throws IOException {
    Path run = dir.resolve("run");
    new RunDirectory(CONFIG, RECORDS).write(run);
    Files.writeString(run.resolve(RunDirectory.DOCUMENTS), RunDirectory.CSV_HEADER + "\n\n");

    assertThat(RunDirectory.read(run).documents()).isEmpty();
  }
}
