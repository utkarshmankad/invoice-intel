package dev.invoiceintel.pii.internal;

import dev.invoiceintel.text.TextPage;
import dev.invoiceintel.text.Word;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The words of a page grouped by position into line fragments: words on one row, split where a wide
 * horizontal gap separates columns. Text-layer order interleaves the lines of side-by-side columns
 * (seller | buyer), so blocks are found from word boxes, not from the joined text (architecture.md
 * §6.3). Distances are relative to the word height, which works for PDF points and OCR pixels
 * alike.
 */
public final class PageLayout {

  /** Words whose vertical centres differ by at most this many heights share a row. */
  static final double ROW_TOLERANCE = 0.5;

  /** A horizontal gap wider than this many heights splits a row into fragments (columns). */
  static final double COLUMN_GAP = 2.0;

  /** A fragment belongs to a block's column if its left edge is this close, in heights. */
  static final double ALIGN_TOLERANCE = 3.0;

  /** A vertical gap above a line of more than this many heights ends a block. */
  static final double BLOCK_GAP = 1.8;

  /**
   * Consecutive words of one row and column.
   *
   * @param words indices into the page's word list, left to right
   */
  public record Fragment(
      int row, List<Integer> words, String text, double left, double top, double bottom) {

    public Fragment {
      words = List.copyOf(words);
    }

    double height() {
      return bottom - top;
    }
  }

  /**
   * A block after an anchor label.
   *
   * @param anchorWords the words of the label itself
   * @param lines content lines in reading order; the first may be the rest of the anchor's line
   */
  public record Block(List<Integer> anchorWords, List<List<Integer>> lines) {

    public Block {
      anchorWords = List.copyOf(anchorWords);
      lines = List.copyOf(lines.stream().map(List::copyOf).toList());
    }
  }

  private final List<Word> words;
  private final List<Fragment> fragments;

  private PageLayout(TextPage page) {
    this.words = page.words();
    this.fragments = buildFragments();
  }

  public static PageLayout of(TextPage page) {
    return new PageLayout(page);
  }

  /** Text of the given words joined by single spaces. */
  public String text(List<Integer> indices) {
    return indices.stream().map(i -> words.get(i).text()).collect(Collectors.joining(" "));
  }

  public Word word(int index) {
    return words.get(index);
  }

  /**
   * Blocks that start at a fragment beginning with one of {@code anchors}: the following lines in
   * the anchor's column, up to a line that starts with one of {@code stops} or a vertical gap. A
   * line after a gap that starts with one of {@code bridges} continues the block instead.
   */
  public List<Block> blocks(List<String> anchors, List<String> stops, List<String> bridges) {
    var blocks = new ArrayList<Block>();
    for (int f = 0; f < fragments.size(); f++) {
      Fragment anchor = fragments.get(f);
      int end = Labels.prefixEnd(anchor.text(), anchors);
      if (end < 0) {
        continue;
      }
      var lines = new ArrayList<List<Integer>>();
      var anchorWords = new ArrayList<Integer>();
      var rest = new ArrayList<Integer>();
      splitAnchor(anchor, end, anchorWords, rest);
      if (!rest.isEmpty()) {
        lines.add(rest);
      }
      Fragment previous = anchor;
      for (int g = f + 1; g < fragments.size(); g++) {
        Fragment next = fragments.get(g);
        if (next.row() == anchor.row() || !aligned(anchor, next)) {
          continue;
        }
        if (Labels.prefixEnd(next.text(), stops) >= 0) {
          break;
        }
        double unit = Math.max(previous.height(), next.height());
        if (next.top() - previous.bottom() > BLOCK_GAP * unit
            && Labels.prefixEnd(next.text(), bridges) < 0) {
          break;
        }
        lines.add(next.words());
        previous = next;
      }
      blocks.add(new Block(anchorWords, lines));
    }
    return blocks;
  }

  /**
   * The words of {@code line} after a label that ends at offset {@code labelEnd} of the line's
   * text. A word that runs on past the label ("Address:Aarav") counts as after it, so nothing after
   * the label is missed.
   */
  public List<Integer> wordsAfter(List<Integer> line, int labelEnd) {
    var rest = new ArrayList<Integer>();
    int offset = 0;
    for (int index : line) {
      String text = words.get(index).text();
      int wordEnd = offset + text.length();
      if (wordEnd > labelEnd && Labels.hasAlnum(text.substring(Math.max(0, labelEnd - offset)))) {
        rest.add(index);
      }
      offset = wordEnd + 1;
    }
    return rest;
  }

  private void splitAnchor(
      Fragment anchor, int labelEnd, List<Integer> anchorWords, List<Integer> rest) {
    rest.addAll(wordsAfter(anchor.words(), labelEnd));
    anchor.words().stream().filter(index -> !rest.contains(index)).forEach(anchorWords::add);
  }

  private static boolean aligned(Fragment anchor, Fragment other) {
    return Math.abs(other.left() - anchor.left()) <= ALIGN_TOLERANCE * anchor.height();
  }

  private List<Fragment> buildFragments() {
    var order = new ArrayList<Integer>();
    for (int i = 0; i < words.size(); i++) {
      order.add(i);
    }
    order.sort(Comparator.comparingDouble(this::centre));

    var rows = new ArrayList<List<Integer>>();
    List<Integer> row = null;
    double rowCentre = 0;
    double rowHeight = 0;
    for (int index : order) {
      Word word = words.get(index);
      if (row == null
          || centre(index) - rowCentre > ROW_TOLERANCE * Math.max(rowHeight, word.h())) {
        row = new ArrayList<>();
        rows.add(row);
        rowCentre = centre(index);
        rowHeight = word.h();
      }
      row.add(index);
    }

    var result = new ArrayList<Fragment>();
    for (int r = 0; r < rows.size(); r++) {
      List<Integer> sorted = new ArrayList<>(rows.get(r));
      sorted.sort(Comparator.comparingDouble(i -> words.get(i).x()));
      var current = new ArrayList<Integer>();
      for (int index : sorted) {
        if (!current.isEmpty() && columnBreak(current.getLast(), index)) {
          result.add(fragment(r, current));
          current = new ArrayList<>();
        }
        current.add(index);
      }
      result.add(fragment(r, current));
    }
    return List.copyOf(result);
  }

  private boolean columnBreak(int left, int right) {
    Word a = words.get(left);
    Word b = words.get(right);
    return b.x() - (a.x() + a.w()) > COLUMN_GAP * Math.max(a.h(), b.h());
  }

  private Fragment fragment(int row, List<Integer> indices) {
    double left = Double.MAX_VALUE;
    double top = Double.MAX_VALUE;
    double bottom = -Double.MAX_VALUE;
    for (int index : indices) {
      Word word = words.get(index);
      left = Math.min(left, word.x());
      top = Math.min(top, word.y());
      bottom = Math.max(bottom, word.y() + word.h());
    }
    return new Fragment(row, indices, text(indices), left, top, bottom);
  }

  private double centre(int index) {
    Word word = words.get(index);
    return word.y() + word.h() / 2;
  }
}
