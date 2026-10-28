package dev.invoiceintel.pii;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.common.fixtures.InvoiceType;
import dev.invoiceintel.common.fixtures.SyntheticInvoice;
import dev.invoiceintel.common.fixtures.SyntheticInvoiceGenerator;
import dev.invoiceintel.text.PdfTextExtractor;
import dev.invoiceintel.text.TextLayer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/** Synthetic text only (testing.md). */
class SpanMergerTest {

  // Word offsets: aa [0,2) bbb [3,6) cc [7,9) dddd [10,14) ee [15,17).
  private static final String TEXT = "aa bbb cc dddd ee";

  private final SpanMerger merger = new SpanMerger();

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          disjoint                       | 0-2 PHONE a; 7-9 EMAIL b                    | 0-2 PHONE a; 7-9 EMAIL b
          adjacent spans stay apart      | 0-2 PERSON a; 3-6 PERSON b                  | 0-2 PERSON a; 3-6 PERSON b
          identical duplicates           | 3-6 PHONE regex:mobile; 3-6 PHONE regex:landline | 3-6 PHONE regex:landline+regex:mobile
          partial overlap, same type     | 0-6 PHONE a; 3-9 PHONE b                    | 0-9 PHONE a+b
          chain A-B, B-C                 | 0-6 PHONE a; 3-9 PHONE b; 7-14 PHONE c      | 0-14 PHONE a+b+c
          nested, same type              | 0-14 PERSON a; 3-6 PERSON b                 | 0-14 PERSON a+b
          partial words expand           | 1-4 EMAIL a                                 | 0-6 EMAIL a
          inside one word                | 11-12 PAN a                                 | 10-14 PAN a
          ends inside next word          | 3-8 PHONE a                                 | 3-9 PHONE a
          only a space                   | 2-3 PHONE a                                 |
          specific inside generic        | 0-17 ADDRESS ner; 7-9 PHONE re              | 0-6 ADDRESS ner; 7-9 PHONE re; 10-17 ADDRESS ner
          generic inside specific        | 0-9 GSTIN a; 3-6 PAN b                      | 0-9 GSTIN a
          partial overlap, other types   | 0-6 PERSON a; 3-9 PHONE b                   | 0-2 PERSON a; 3-9 PHONE b
          specific at the edge           | 0-9 ADDRESS a; 7-9 PIN_CODE b               | 0-6 ADDRESS a; 7-9 PIN_CODE b
          linked through a generic span  | 0-2 PERSON a; 3-6 PERSON b; 0-6 ADDRESS c   | 0-6 PERSON a+b
          order beats invoice            | 3-9 INVOICE_NUMBER a; 3-9 ORDER_NUMBER b    | 3-9 ORDER_NUMBER b
          aadhaar beats pan              | 3-6 PAN a; 3-6 AADHAAR b                    | 3-6 AADHAAR b
          email beats phone              | 3-6 PHONE a; 3-6 EMAIL b                    | 3-6 EMAIL b
          phone beats pin                | 3-6 PIN_CODE a; 3-6 PHONE b                 | 3-6 PHONE b
          pin beats person               | 3-6 PERSON a; 3-6 PIN_CODE b                | 3-6 PIN_CODE b
          person beats address           | 3-6 ADDRESS a; 3-6 PERSON b                 | 3-6 PERSON b
          whole page                     | 0-17 ADDRESS a                              | 0-17 ADDRESS a
          """)
  void merge_overlapTable_givesExpectedSpans(String name, String input, String expected) {
    List<PiiSpan> merged = merger.merge(layer(TEXT), spans(1, input));

    assertThat(merged).containsExactlyElementsOf(spans(1, expected));
  }

  @Test
  void merge_inputOrder_doesNotMatter() {
    List<PiiSpan> input = spans(1, "0-17 ADDRESS ner; 7-9 PHONE re; 3-9 PHONE x; 1-2 PERSON p");
    List<PiiSpan> reversed = new ArrayList<>(input);
    Collections.reverse(reversed);

    assertThat(merger.merge(layer(TEXT), reversed)).isEqualTo(merger.merge(layer(TEXT), input));
  }

  @Test
  void merge_emptyInput_givesNothing() {
    assertThat(merger.merge(layer(TEXT), List.of())).isEmpty();
  }

  @Test
  void merge_severalPages_mergesPerPageInPageOrder() {
    var layer =
        new TextLayer(
            List.of(
                RegexDetectorTest.page(1, TEXT),
                RegexDetectorTest.page(2, "xx yy"),
                RegexDetectorTest.page(3, TEXT)));
    var input = new ArrayList<PiiSpan>(spans(3, "0-6 PHONE a; 3-9 PHONE b"));
    input.addAll(spans(1, "3-6 EMAIL c; 4-5 EMAIL d"));

    assertThat(merger.merge(layer, input))
        .containsExactly(
            new PiiSpan(1, 3, 6, PiiType.EMAIL, "c+d"), new PiiSpan(3, 0, 9, PiiType.PHONE, "a+b"));
  }

  @Test
  void merge_spanOnPageNotInLayer_isRejectedWithoutValues() {
    List<PiiSpan> input = List.of(new PiiSpan(2, 0, 2, PiiType.PHONE, "a"));

    assertThatThrownBy(() -> merger.merge(layer(TEXT), input))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("span on page 2 not in layer");
  }

  @Test
  void merge_spanBeyondPageText_isRejectedWithoutValues() {
    List<PiiSpan> input = List.of(new PiiSpan(1, 15, 18, PiiType.PHONE, "a"));

    assertThatThrownBy(() -> merger.merge(layer(TEXT), input))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("span ends beyond page 1 text");
  }

  @Test
  void merge_spanEndingAtPageEnd_isAccepted() {
    assertThat(merger.merge(layer(TEXT), spans(1, "16-17 PHONE a"))).hasSize(1);
  }

  @Test
  void merge_layerWithRepeatedPage_isRejected() {
    var layer =
        new TextLayer(List.of(RegexDetectorTest.page(1, TEXT), RegexDetectorTest.page(1, TEXT)));

    assertThatThrownBy(() -> merger.merge(layer, List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("layer has page 1 twice");
  }

  @Test
  void priority_ranksEveryType() {
    assertThat(SpanMerger.PRIORITY).containsExactlyInAnyOrder(PiiType.values());
  }

  static Stream<Arguments> fixtures() {
    return Arrays.stream(InvoiceType.values())
        .flatMap(type -> Stream.of(3L, 17L, 99L).map(seed -> Arguments.of(type, seed)));
  }

  @ParameterizedTest(name = "{0} seed {1}")
  @MethodSource("fixtures")
  void merge_regexSpansOnSyntheticInvoice_coverEveryRawSpanWithoutOverlap(
      InvoiceType type, long seed) {
    SyntheticInvoice invoice = SyntheticInvoiceGenerator.generate(type, seed);
    TextLayer layer = new PdfTextExtractor().extract(SyntheticInvoiceGenerator.toPdf(invoice));
    List<PiiSpan> raw = new RegexDetector().detect(layer, new DocumentContext(DocumentId.random()));

    List<PiiSpan> merged = merger.merge(layer, raw);

    assertThat(raw).isNotEmpty();
    for (int i = 1; i < merged.size(); i++) {
      PiiSpan prev = merged.get(i - 1);
      PiiSpan next = merged.get(i);
      assertThat(prev.pageNo() < next.pageNo() || prev.end() < next.start())
          .as("span %d is after span %d without overlap", i, i - 1)
          .isTrue();
    }
    for (PiiSpan span : raw) {
      String text = PageText.of(layer.pages().get(span.pageNo() - 1)).text();
      for (int c = span.start(); c < span.end(); c++) {
        int pos = c;
        if (text.charAt(pos) != ' ') {
          assertThat(merged)
              .as("character %d of page %d is covered", pos, span.pageNo())
              .anyMatch(m -> m.pageNo() == span.pageNo() && m.start() <= pos && pos < m.end());
        }
      }
    }
    for (PiiSpan span : merged) {
      String text = PageText.of(layer.pages().get(span.pageNo() - 1)).text();
      assertThat(span.start() == 0 || text.charAt(span.start() - 1) == ' ').isTrue();
      assertThat(span.end() == text.length() || text.charAt(span.end()) == ' ').isTrue();
    }
  }

  /** Parses {@code "start-end TYPE detector; ..."}; a blank table cell is no spans. */
  private static List<PiiSpan> spans(int pageNo, String table) {
    if (table == null || table.isBlank()) {
      return List.of();
    }
    return Arrays.stream(table.split(";"))
        .map(String::strip)
        .map(
            entry -> {
              String[] parts = entry.split(" ");
              String[] range = parts[0].split("-");
              return new PiiSpan(
                  pageNo,
                  Integer.parseInt(range[0]),
                  Integer.parseInt(range[1]),
                  PiiType.valueOf(parts[1]),
                  parts[2]);
            })
        .toList();
  }

  private static TextLayer layer(String text) {
    return new TextLayer(List.of(RegexDetectorTest.page(1, text)));
  }
}
