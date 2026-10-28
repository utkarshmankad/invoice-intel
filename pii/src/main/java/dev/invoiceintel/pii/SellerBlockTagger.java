package dev.invoiceintel.pii;

import dev.invoiceintel.pii.internal.Labels;
import dev.invoiceintel.pii.internal.PageLayout;
import dev.invoiceintel.text.TextLayer;
import dev.invoiceintel.text.TextPage;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Tags the seller block as seller data before detection runs (architecture.md §6.3): the lines
 * after a "Sold By", "Seller" or "Supplier" anchor in the anchor's column, up to the next known
 * label or a vertical gap, plus seller ID lines (PAN, GSTIN, VAT/TIN...) right below it even after
 * a gap. Seller name, address and IDs are not PII (specs.md §6.1).
 */
public final class SellerBlockTagger {

  private static final List<String> STOPS =
      Stream.of(Labels.SECTION_LABELS, Labels.BUYER_ANCHORS, Labels.SELLER_ANCHORS)
          .flatMap(List::stream)
          .toList();

  /** Ranges of each page's {@link PageText} that hold seller data. */
  public List<TextRange> tag(TextLayer layer) {
    var ranges = new ArrayList<TextRange>();
    for (TextPage page : layer.pages()) {
      PageLayout layout = PageLayout.of(page);
      var words = new ArrayList<Integer>();
      for (PageLayout.Block block :
          layout.blocks(Labels.SELLER_ANCHORS, STOPS, Labels.SELLER_ID_LABELS)) {
        words.addAll(block.anchorWords());
        block.lines().forEach(words::addAll);
      }
      ranges.addAll(WordRuns.ranges(PageText.of(page), words));
    }
    return List.copyOf(ranges);
  }
}
