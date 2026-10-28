package dev.invoiceintel.pii;

import dev.invoiceintel.text.TextLayer;
import dev.invoiceintel.text.TextPage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Turns the overlapping, repeated spans of all detectors into one non-overlapping set per page
 * (architecture.md §6.3). Biased toward recall (specs.md §6.3): every character any detector
 * covered stays covered, and a span touching part of a word covers the whole word.
 *
 * <p>Works on words: each word covered by any span takes the most specific type among the spans
 * covering it ({@link #PRIORITY}). Consecutive covered words form one output span when they have
 * the same type and some input span covers both, so the space between two words stays inside a span
 * only when both sides are covered. A phone inside an address block therefore stays a separate
 * {@code PHONE} span and the address around it stays {@code ADDRESS}, so each entity gets the token
 * of its own type (specs.md §6.1). Spans that merely sit next to each other are not joined. Output
 * spans carry the sorted, distinct names of the contributing detectors joined by {@code +}; like
 * the input, they hold positions and rule names only (PII-8).
 */
public final class SpanMerger {

  /**
   * Most specific first. Checksummed identifiers beat format-only ones (a PAN inside a GSTIN is the
   * GSTIN), document numbers beat contact details, and contact details beat the generic name and
   * address text that NER or the address block detector put around them.
   */
  static final List<PiiType> PRIORITY =
      List.of(
          PiiType.GSTIN,
          PiiType.AADHAAR,
          PiiType.PAN,
          PiiType.ORDER_NUMBER,
          PiiType.INVOICE_NUMBER,
          PiiType.EMAIL,
          PiiType.PHONE,
          PiiType.PIN_CODE,
          PiiType.PERSON,
          PiiType.ADDRESS);

  private static final Map<PiiType, Integer> RANK = new EnumMap<>(PiiType.class);

  static {
    for (int i = 0; i < PRIORITY.size(); i++) {
      RANK.put(PRIORITY.get(i), i);
    }
  }

  /**
   * Merges {@code spans} found on {@code layer}.
   *
   * @return non-overlapping spans on whole words, sorted by page and start
   * @throws IllegalArgumentException if a span's page is not in the layer or it ends beyond the
   *     page text
   */
  public List<PiiSpan> merge(TextLayer layer, Collection<PiiSpan> spans) {
    Objects.requireNonNull(layer, "layer");
    Objects.requireNonNull(spans, "spans");
    Map<Integer, PageText> pages = new HashMap<>();
    for (TextPage page : layer.pages()) {
      if (pages.put(page.pageNo(), PageText.of(page)) != null) {
        throw new IllegalArgumentException("layer has page " + page.pageNo() + " twice");
      }
    }
    Map<Integer, List<PiiSpan>> byPage = new HashMap<>();
    for (PiiSpan span : spans) {
      PageText page = pages.get(span.pageNo());
      if (page == null) {
        throw new IllegalArgumentException("span on page " + span.pageNo() + " not in layer");
      }
      if (span.end() > page.text().length()) {
        throw new IllegalArgumentException("span ends beyond page " + span.pageNo() + " text");
      }
      byPage.computeIfAbsent(span.pageNo(), k -> new ArrayList<>()).add(span);
    }
    var result = new ArrayList<PiiSpan>();
    byPage.keySet().stream()
        .sorted()
        .forEach(pageNo -> result.addAll(mergePage(pages.get(pageNo), byPage.get(pageNo))));
    return List.copyOf(result);
  }

  private static List<PiiSpan> mergePage(PageText page, List<PiiSpan> spans) {
    int n = page.wordCount();
    PiiType[] label = new PiiType[n];
    // linked[i]: one input span covers both word i and word i + 1.
    boolean[] linked = new boolean[n];
    var ranges = new ArrayList<int[]>(spans.size());
    for (PiiSpan span : spans) {
      int[] words = wordRange(page, span);
      ranges.add(words);
      if (words.length == 0) {
        continue; // only the space between two words: nothing to redact
      }
      for (int i = words[0]; i <= words[1]; i++) {
        if (label[i] == null || RANK.get(span.type()) < RANK.get(label[i])) {
          label[i] = span.type();
        }
        if (i < words[1]) {
          linked[i] = true;
        }
      }
    }
    var merged = new ArrayList<PiiSpan>();
    int i = 0;
    while (i < n) {
      if (label[i] == null) {
        i++;
        continue;
      }
      int first = i;
      PiiType type = label[i];
      while (i + 1 < n && linked[i] && label[i + 1] == type) {
        i++;
      }
      merged.add(
          new PiiSpan(
              page.pageNo(),
              page.wordStart(first),
              page.wordEnd(i),
              type,
              detectors(spans, ranges, type, first, i)));
      i++;
    }
    return merged;
  }

  /** Indexes of the first and last word the span touches, or an empty array if none. */
  private static int[] wordRange(PageText page, PiiSpan span) {
    int first = -1;
    int last = -1;
    for (int w = 0; w < page.wordCount(); w++) {
      if (page.wordStart(w) < span.end() && page.wordEnd(w) > span.start()) {
        if (first < 0) {
          first = w;
        }
        last = w;
      }
    }
    return first < 0 ? new int[0] : new int[] {first, last};
  }

  private static String detectors(
      List<PiiSpan> spans, List<int[]> ranges, PiiType type, int first, int last) {
    var names = new TreeSet<String>();
    for (int s = 0; s < spans.size(); s++) {
      int[] words = ranges.get(s);
      if (spans.get(s).type() == type
          && words.length > 0
          && words[0] <= last
          && words[1] >= first) {
        names.add(spans.get(s).detector());
      }
    }
    return String.join("+", names);
  }
}
