package dev.invoiceintel.pii;

import dev.invoiceintel.text.TextLayer;
import dev.invoiceintel.text.TextPage;
import dev.invoiceintel.text.Word;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Rebuilds the text layer with PII words replaced, giving the {@link RedactedContent} that is the
 * only way to a remote model (PII-1, PII-3, architecture.md §6.3, §6.4).
 *
 * <ul>
 *   <li>Spans that are wholly seller data ({@link DocumentContext#isSellerData}) are dropped first:
 *       seller details are not PII (specs.md §6.1).
 *   <li>Each remaining span's value (its words joined by single spaces) gets a replacement: in
 *       {@link RedactionMode#PSEUDONYMIZE} mode the {@link TokenGenerator} token plus a {@link
 *       VaultEntry}; in {@link RedactionMode#REDACT} mode {@code [REDACTED:TYPE]} and no entry.
 *       Order and invoice numbers get their token in both modes, because the spec requires a
 *       consistent token for them even when redacting (§6.1, reconciliation joins on it); in redact
 *       mode they get no vault entry, so they stay irreversible. PIN codes are removed in both
 *       modes (§6.1): {@code [REDACTED:PIN_CODE]}, no entry.
 *   <li>The words of a span become one replacement word per line: a span that runs over several
 *       lines gets one replacement word on each, with the same text, whose box is the union of that
 *       line's covered words and whose confidence is their lowest. A box never spans two lines, so
 *       it can't cover the text of another column between them. Words outside spans keep their text
 *       and box exactly.
 * </ul>
 *
 * <p>It expects {@link SpanMerger} output: spans that touch the same word are rejected, and a span
 * touching part of a word replaces the whole word (recall, specs.md §6.3). Exceptions name page
 * numbers only, never values (PII-8).
 */
public final class TextRedactor {

  /** Words whose vertical centres differ by at most this many word heights share a line. */
  static final double ROW_TOLERANCE = 0.5;

  private final TokenGenerator tokens;
  private final PiiVault vault;

  public TextRedactor(TokenGenerator tokens, PiiVault vault) {
    this.tokens = Objects.requireNonNull(tokens, "tokens");
    this.vault = Objects.requireNonNull(vault, "vault");
  }

  /**
   * The redaction of a document.
   *
   * @param content the redacted document
   * @param spans the spans that were replaced (seller spans removed), for {@code pii_span} rows and
   *     the image redactor
   * @param replacements the replacement text of each span, in the order of {@code spans}: its
   *     token, or the {@code [REDACTED:TYPE]} marker
   * @param vaultEntries one entry per distinct token, to store in {@code pii_vault}; empty in
   *     redact mode
   */
  public record Result(
      RedactedContent content,
      List<PiiSpan> spans,
      List<String> replacements,
      List<VaultEntry> vaultEntries) {

    public Result {
      Objects.requireNonNull(content, "content");
      spans = List.copyOf(spans);
      replacements = List.copyOf(replacements);
      if (replacements.size() != spans.size()) {
        throw new IllegalArgumentException("one replacement per span expected");
      }
      vaultEntries = List.copyOf(vaultEntries);
    }

    @Override
    public String toString() {
      return "TextRedactor.Result[content="
          + content
          + ", spans="
          + spans.size()
          + ", vaultEntries="
          + vaultEntries.size()
          + "]";
    }
  }

  /**
   * Redacts {@code layer}.
   *
   * @param spans merged spans of all detectors ({@link SpanMerger#merge})
   * @throws IllegalArgumentException if a span's page is not in the layer, it ends beyond the page
   *     text, or two spans touch the same word
   */
  public Result redact(
      TextLayer layer, Collection<PiiSpan> spans, DocumentContext ctx, RedactionMode mode) {
    Objects.requireNonNull(layer, "layer");
    Objects.requireNonNull(spans, "spans");
    Objects.requireNonNull(ctx, "ctx");
    Objects.requireNonNull(mode, "mode");

    Map<Integer, PageText> texts = new HashMap<>();
    for (TextPage page : layer.pages()) {
      if (texts.put(page.pageNo(), PageText.of(page)) != null) {
        throw new IllegalArgumentException("layer has page " + page.pageNo() + " twice");
      }
    }
    var kept = new ArrayList<PiiSpan>();
    Map<Integer, List<PiiSpan>> byPage = new HashMap<>();
    for (PiiSpan span : spans) {
      PageText text = texts.get(span.pageNo());
      if (text == null) {
        throw new IllegalArgumentException("span on page " + span.pageNo() + " not in layer");
      }
      if (span.end() > text.text().length()) {
        throw new IllegalArgumentException("span ends beyond page " + span.pageNo() + " text");
      }
      if (!ctx.isSellerData(span)) {
        kept.add(span);
        byPage.computeIfAbsent(span.pageNo(), k -> new ArrayList<>()).add(span);
      }
    }

    Map<String, VaultEntry> entries = new LinkedHashMap<>();
    Map<PiiSpan, String> replaced = new HashMap<>();
    var pages = new ArrayList<RedactedContent.Page>();
    for (TextPage page : layer.pages()) {
      List<PiiSpan> pageSpans = byPage.getOrDefault(page.pageNo(), List.of());
      pages.add(redactPage(page, texts.get(page.pageNo()), pageSpans, mode, entries, replaced));
    }
    kept.sort(
        (a, b) ->
            a.pageNo() != b.pageNo()
                ? Integer.compare(a.pageNo(), b.pageNo())
                : Integer.compare(a.start(), b.start()));
    return new Result(
        new RedactedContent(ctx.documentId(), mode, pages),
        kept,
        kept.stream().map(replaced::get).toList(),
        List.copyOf(entries.values()));
  }

  private RedactedContent.Page redactPage(
      TextPage page,
      PageText text,
      List<PiiSpan> spans,
      RedactionMode mode,
      Map<String, VaultEntry> entries,
      Map<PiiSpan, String> replaced) {
    var replacements = new ArrayList<String>(spans.size());
    for (PiiSpan span : spans) {
      String value = text.text().substring(span.start(), span.end());
      String replacement = replace(span.type(), value, mode, entries);
      replacements.add(replacement);
      replaced.put(span, replacement);
    }
    return replaceWords(page, text, spans, replacements);
  }

  /**
   * {@code page} with the words of each span replaced by its replacement text, one word per line of
   * the span (see the class comment). {@link RedactedContentLoader} calls it with the stored spans
   * and tokens, so a reloaded page has the same words as the one the PII stage built.
   *
   * @throws IllegalArgumentException if two spans touch the same word
   */
  static RedactedContent.Page replaceWords(
      TextPage page, PageText text, List<PiiSpan> spans, List<String> replacements) {
    List<Word> words = page.words();
    // owner[i]: index in spans of the span covering word i, or -1 when the word is kept.
    int[] owner = new int[words.size()];
    Arrays.fill(owner, -1);
    for (int s = 0; s < spans.size(); s++) {
      PiiSpan span = spans.get(s);
      for (int i = 0; i < words.size(); i++) {
        if (text.wordStart(i) < span.end() && text.wordEnd(i) > span.start()) {
          if (owner[i] >= 0) {
            throw new IllegalArgumentException(
                "spans overlap on page " + page.pageNo() + "; merge them first");
          }
          owner[i] = s;
        }
      }
    }
    var out = new ArrayList<Word>(words.size());
    int i = 0;
    while (i < words.size()) {
      if (owner[i] < 0) {
        out.add(words.get(i));
        i++;
        continue;
      }
      // One replacement word per line of a span's words. Two adjacent spans with the same value
      // still give two words.
      int first = i;
      while (i + 1 < words.size()
          && owner[i + 1] == owner[first]
          && sameLine(words.get(i), words.get(i + 1))) {
        i++;
      }
      out.add(union(replacements.get(owner[first]), words.subList(first, i + 1)));
      i++;
    }
    return new RedactedContent.Page(page.pageNo(), page.width(), page.height(), out);
  }

  private String replace(
      PiiType type, String value, RedactionMode mode, Map<String, VaultEntry> entries) {
    if (type == PiiType.PIN_CODE) {
      return marker(type);
    }
    boolean documentNumber = type == PiiType.ORDER_NUMBER || type == PiiType.INVOICE_NUMBER;
    if (mode == RedactionMode.REDACT) {
      return documentNumber ? tokens.token(type, value) : marker(type);
    }
    String token = tokens.token(type, value);
    entries.computeIfAbsent(token, t -> vault.seal(t, type, value));
    return token;
  }

  static String marker(PiiType type) {
    return "[REDACTED:" + type.name() + "]";
  }

  static boolean sameLine(Word a, Word b) {
    double centreA = a.y() + a.h() / 2;
    double centreB = b.y() + b.h() / 2;
    return Math.abs(centreA - centreB) <= ROW_TOLERANCE * Math.max(a.h(), b.h());
  }

  private static Word union(String text, List<Word> words) {
    double left = Double.MAX_VALUE;
    double top = Double.MAX_VALUE;
    double right = -Double.MAX_VALUE;
    double bottom = -Double.MAX_VALUE;
    double confidence = Double.MAX_VALUE;
    for (Word word : words) {
      left = Math.min(left, word.x());
      top = Math.min(top, word.y());
      right = Math.max(right, word.x() + word.w());
      bottom = Math.max(bottom, word.y() + word.h());
      confidence = Math.min(confidence, word.confidence());
    }
    return new Word(text, left, top, right - left, bottom - top, confidence);
  }
}
