package dev.invoiceintel.pii;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.text.TextLayer;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Hand-made layouts with made-up values (testing.md); fixture invoices: AddressBlockDetectorTest.
 */
class SellerBlockTaggerTest {

  private final SellerBlockTagger tagger = new SellerBlockTagger();

  private static boolean tagged(TextLayer layer, String value) {
    String text = PageText.of(layer.pages().getFirst()).text();
    int at = text.indexOf(value);
    assertThat(at).as("value present").isNotNegative();
    DocumentContext ctx = DocumentContext.of(DocumentId.random(), layer);
    return ctx.isSellerData(new PiiSpan(1, at, at + value.length(), PiiType.ADDRESS, "t"));
  }

  @Test
  void tag_sellerIdAfterGap_isBridged() {
    TextLayer layer =
        Layouts.page(
            Layouts.line(40, 100, "Sold By :"),
            Layouts.line(40, 112, "Fixture Traders LLP"),
            Layouts.line(40, 124, "Unit 4, Ring Road"),
            Layouts.line(40, 160, "GSTIN: 07FIXTURE01"),
            Layouts.line(40, 172, "PAN No: FIXTUREPAN"),
            Layouts.line(40, 220, "Rohan Fixturekar"));

    assertThat(tagged(layer, "Fixture Traders LLP")).isTrue();
    assertThat(tagged(layer, "Unit 4, Ring Road")).isTrue();
    assertThat(tagged(layer, "GSTIN: 07FIXTURE01")).isTrue();
    assertThat(tagged(layer, "PAN No: FIXTUREPAN")).isTrue();
    assertThat(tagged(layer, "Rohan Fixturekar")).isFalse();
  }

  @Test
  void tag_nameOnAnchorLine_isTagged() {
    TextLayer layer =
        Layouts.page(
            Layouts.line(40, 100, "Seller: Demotronics"), Layouts.line(40, 112, "Mumbai 400001"));

    assertThat(tagged(layer, "Seller: Demotronics")).isTrue();
    assertThat(tagged(layer, "Mumbai 400001")).isTrue();
  }

  @Test
  void tag_otherColumnAndNextSection_areNotTagged() {
    TextLayer layer =
        Layouts.page(
            Layouts.line(40, 100, "Supplier"),
            Layouts.line(40, 112, "Fixture Traders"),
            Layouts.line(320, 100, "Billing Address"),
            Layouts.line(320, 112, "Rohan Fixturekar"),
            Layouts.line(40, 124, "Invoice Number: 42"));

    assertThat(tagged(layer, "Fixture Traders")).isTrue();
    assertThat(tagged(layer, "Rohan Fixturekar")).isFalse();
    assertThat(tagged(layer, "Invoice Number: 42")).isFalse();
  }

  @Test
  void tag_noSellerAnchor_tagsNothing() {
    TextLayer layer =
        Layouts.page(
            Layouts.line(40, 100, "Sellers' market"), Layouts.line(40, 112, "Fixture Traders"));

    assertThat(tagger.tag(layer)).isEmpty();
  }

  @Test
  void isSellerData_spanCrossingRangeEnd_isNotSeller() {
    var ctx = new DocumentContext(DocumentId.random(), List.of(new TextRange(1, 10, 20)));

    assertThat(ctx.isSellerData(new PiiSpan(1, 10, 20, PiiType.PAN, "t"))).isTrue();
    assertThat(ctx.isSellerData(new PiiSpan(1, 12, 21, PiiType.PAN, "t"))).isFalse();
    assertThat(ctx.isSellerData(new PiiSpan(1, 9, 15, PiiType.PAN, "t"))).isFalse();
    assertThat(ctx.isSellerData(new PiiSpan(2, 12, 15, PiiType.PAN, "t"))).isFalse();
    assertThat(new DocumentContext(DocumentId.random()).sellerRanges()).isEmpty();
  }

  @Test
  void textRange_invalidBounds_areRejected() {
    assertThatThrownBy(() -> new TextRange(0, 0, 1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TextRange(1, -1, 1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TextRange(1, 3, 3)).isInstanceOf(IllegalArgumentException.class);
  }
}
