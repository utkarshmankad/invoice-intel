package dev.invoiceintel.reconcile.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CsvReaderTest {

  @Test
  void read_plainRows_splitsOnCommasAndLineEnds() {
    assertThat(CsvReader.read("a,b\r\n1,2\n3,4"))
        .contains(List.of(List.of("a", "b"), List.of("1", "2"), List.of("3", "4")));
  }

  @Test
  void read_quotedFields_keepCommasLineBreaksAndDoubledQuotes() {
    var csv = "name,note\n\"Pen, blue\",\"line1\nline2 \"\"x\"\"\"\n";
    assertThat(CsvReader.read(csv))
        .contains(List.of(List.of("name", "note"), List.of("Pen, blue", "line1\nline2 \"x\"")));
  }

  @Test
  void read_byteOrderMarkAndBlankLines_areIgnored() {
    assertThat(CsvReader.read("﻿a,b\n\n1,2\n\n"))
        .contains(List.of(List.of("a", "b"), List.of("1", "2")));
  }

  @Test
  void read_emptyFields_areKept() {
    assertThat(CsvReader.read("a,,c\n,,\n\"\""))
        .contains(List.of(List.of("a", "", "c"), List.of("", "", ""), List.of("")));
  }

  @Test
  void read_unclosedQuote_isEmpty() {
    assertThat(CsvReader.read("a,b\n\"open,2\n")).isEmpty();
  }

  @Test
  void read_emptyText_hasNoRecords() {
    assertThat(CsvReader.read("")).contains(List.of());
  }
}
