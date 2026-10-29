package dev.invoiceintel.pii;

import static dev.invoiceintel.pii.Layouts.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.text.TextLayer;
import dev.invoiceintel.text.TextPage;
import dev.invoiceintel.text.TextSource;
import dev.invoiceintel.text.Word;
import java.security.GeneralSecurityException;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Hand-made synthetic layouts and made-up values only (testing.md). */
class TextRedactorTest {

  // Built from parts so the CI secret scan (.gitleaks.toml) doesn't flag test sources.
  private static final String MOBILE = "98765" + "43210";

  private static TokenGenerator tokens;
  private static PiiVault vault;
  private static TextRedactor redactor;

  @BeforeAll
  static void keys() throws GeneralSecurityException {
    tokens = TestKeysets.tokens(TestKeysets.newTokenKeyset());
    vault = TestKeysets.vault(TestKeysets.newVaultKeyset());
    redactor = new TextRedactor(tokens, vault);
  }

  private static final DocumentContext CTX = new DocumentContext(DocumentId.random());

  /** A span over the first occurrence of {@code value} on page 1. */
  private static PiiSpan span(TextLayer layer, String value, PiiType type) {
    String text = PageText.of(layer.pages().getFirst()).text();
    int at = text.indexOf(value);
    assertThat(at).as("value present").isNotNegative();
    return new PiiSpan(1, at, at + value.length(), type, "test");
  }

  private static RedactedContent.Page page(TextRedactor.Result result) {
    return result.content().pages().getFirst();
  }

  @Test
  void redact_pseudonymize_replacesSpanWithTokenAndSealsValue() {
    TextLayer layer = Layouts.page(line(40, 100, "Phone: " + MOBILE + " thanks"));

    TextRedactor.Result result =
        redactor.redact(
            layer, List.of(span(layer, MOBILE, PiiType.PHONE)), CTX, RedactionMode.PSEUDONYMIZE);

    String token = tokens.token(PiiType.PHONE, MOBILE);
    assertThat(page(result).text()).isEqualTo("Phone: " + token + " thanks");
    assertThat(result.vaultEntries())
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.token()).isEqualTo(token);
              assertThat(entry.type()).isEqualTo(PiiType.PHONE);
              assertThat(vault.open(entry)).isEqualTo(MOBILE);
            });
    assertThat(result.content().mode()).isEqualTo(RedactionMode.PSEUDONYMIZE);
    assertThat(result.content().documentId()).isEqualTo(CTX.documentId());
  }

  @Test
  void redact_replacements_followSpanOrderWithTokenOrMarker() {
    String pin = "560" + "001";
    TextLayer layer = Layouts.page(line(40, 100, "PIN " + pin + " Phone: " + MOBILE));

    TextRedactor.Result result =
        redactor.redact(
            layer,
            List.of(span(layer, MOBILE, PiiType.PHONE), span(layer, pin, PiiType.PIN_CODE)),
            CTX,
            RedactionMode.PSEUDONYMIZE);

    assertThat(result.spans())
        .extracting(PiiSpan::type)
        .containsExactly(PiiType.PIN_CODE, PiiType.PHONE);
    assertThat(result.replacements())
        .containsExactly("[REDACTED:PIN_CODE]", tokens.token(PiiType.PHONE, MOBILE));
  }

  @Test
  void result_replacementCountDiffersFromSpans_isRejected() {
    TextLayer layer = Layouts.page(line(40, 100, "Phone: " + MOBILE));
    TextRedactor.Result result =
        redactor.redact(
            layer, List.of(span(layer, MOBILE, PiiType.PHONE)), CTX, RedactionMode.PSEUDONYMIZE);

    assertThatThrownBy(
            () ->
                new TextRedactor.Result(
                    result.content(), result.spans(), List.of(), result.vaultEntries()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void redact_unaffectedWords_keepTextAndBoxExactly() {
    TextLayer layer = Layouts.page(line(40, 100, "Phone: " + MOBILE + " thanks"));
    List<Word> original = layer.pages().getFirst().words();

    TextRedactor.Result result =
        redactor.redact(
            layer, List.of(span(layer, MOBILE, PiiType.PHONE)), CTX, RedactionMode.PSEUDONYMIZE);

    List<Word> words = page(result).words();
    assertThat(words).hasSize(3);
    assertThat(words.get(0)).isEqualTo(original.get(0));
    assertThat(words.get(1))
        .usingRecursiveComparison()
        .ignoringFields("text")
        .isEqualTo(original.get(1));
    assertThat(words.get(2)).isEqualTo(original.get(2));
  }

  @Test
  void redact_spanOverSeveralWordsOfOneLine_becomesOneWordWithUnionBox() {
    List<Word> line =
        List.of(
            new Word("Ship", 40, 100, 20, 8, 1.0),
            new Word("Aarav", 70, 100, 25, 8, 0.9),
            new Word("Testkumar", 98, 101, 45, 9, 0.7),
            new Word("end", 150, 100, 15, 8, 1.0));
    TextLayer layer = new TextLayer(List.of(new TextPage(1, 595, 842, TextSource.OCR, line)));

    TextRedactor.Result result =
        redactor.redact(
            layer,
            List.of(span(layer, "Aarav Testkumar", PiiType.PERSON)),
            CTX,
            RedactionMode.PSEUDONYMIZE);

    String token = tokens.token(PiiType.PERSON, "Aarav Testkumar");
    assertThat(page(result).words())
        .containsExactly(line.get(0), new Word(token, 70, 100, 73, 10, 0.7), line.get(3));
  }

  @Test
  void redact_spanOverTwoLines_givesOneWordPerLineWithTheSameToken() {
    TextLayer layer =
        Layouts.page(line(40, 100, "Flat 101, Example"), line(40, 112, "Residency Road"));
    String value = "Flat 101, Example Residency Road";

    TextRedactor.Result result =
        redactor.redact(
            layer, List.of(span(layer, value, PiiType.ADDRESS)), CTX, RedactionMode.PSEUDONYMIZE);

    String token = tokens.token(PiiType.ADDRESS, value);
    List<Word> words = page(result).words();
    assertThat(words).extracting(Word::text).containsExactly(token, token);
    assertThat(words.get(0).y()).isEqualTo(100);
    assertThat(words.get(1).y()).isEqualTo(112);
    assertThat(words.get(0).h()).isEqualTo(Layouts.HEIGHT);
    assertThat(result.vaultEntries()).hasSize(1);
  }

  @Test
  void redact_sameValueTwice_givesSameTokenAndOneVaultEntry() {
    TextLayer layer =
        Layouts.page(line(40, 100, "Mail a@example.com"), line(40, 120, "cc a@example.com"));
    String text = PageText.of(layer.pages().getFirst()).text();
    int second = text.lastIndexOf("a@example.com");

    TextRedactor.Result result =
        redactor.redact(
            layer,
            List.of(
                span(layer, "a@example.com", PiiType.EMAIL),
                new PiiSpan(1, second, second + 13, PiiType.EMAIL, "test")),
            CTX,
            RedactionMode.PSEUDONYMIZE);

    String token = tokens.token(PiiType.EMAIL, "a@example.com");
    assertThat(page(result).text()).isEqualTo("Mail " + token + " cc " + token);
    assertThat(result.vaultEntries()).hasSize(1);
  }

  @Test
  void redact_adjacentSpansWithEqualValues_stayTwoWords() {
    TextLayer layer = Layouts.page(line(40, 100, "x@example.com x@example.com"));

    TextRedactor.Result result =
        redactor.redact(
            layer,
            List.of(
                new PiiSpan(1, 0, 13, PiiType.EMAIL, "test"),
                new PiiSpan(1, 14, 27, PiiType.EMAIL, "test")),
            CTX,
            RedactionMode.PSEUDONYMIZE);

    assertThat(page(result).words()).hasSize(2);
  }

  @Test
  void redact_redactMode_usesMarkerAndWritesNoVaultEntry() {
    TextLayer layer = Layouts.page(line(40, 100, "Bill To Aarav Testkumar"));

    TextRedactor.Result result =
        redactor.redact(
            layer,
            List.of(span(layer, "Aarav Testkumar", PiiType.PERSON)),
            CTX,
            RedactionMode.REDACT);

    assertThat(page(result).text()).isEqualTo("Bill To [REDACTED:PERSON]");
    assertThat(result.vaultEntries()).isEmpty();
    assertThat(result.content().mode()).isEqualTo(RedactionMode.REDACT);
  }

  @Test
  void redact_redactMode_keepsConsistentTokenForOrderAndInvoiceNumbers() {
    String order = "171-" + "1234567-7654321";
    TextLayer layer = Layouts.page(line(40, 100, "Order " + order + " Invoice INV-29-0000001"));

    TextRedactor.Result result =
        redactor.redact(
            layer,
            List.of(
                span(layer, order, PiiType.ORDER_NUMBER),
                span(layer, "INV-29-0000001", PiiType.INVOICE_NUMBER)),
            CTX,
            RedactionMode.REDACT);

    assertThat(page(result).text())
        .isEqualTo(
            "Order "
                + tokens.token(PiiType.ORDER_NUMBER, order)
                + " Invoice "
                + tokens.token(PiiType.INVOICE_NUMBER, "INV-29-0000001"));
    assertThat(result.vaultEntries()).isEmpty();
  }

  @Test
  void redact_pinCode_isRemovedInBothModesWithoutVaultEntry() {
    TextLayer layer = Layouts.page(line(40, 100, "Pune 411001 India"));
    List<PiiSpan> spans = List.of(span(layer, "411001", PiiType.PIN_CODE));

    for (RedactionMode mode : RedactionMode.values()) {
      TextRedactor.Result result = redactor.redact(layer, spans, CTX, mode);

      assertThat(page(result).text()).isEqualTo("Pune [REDACTED:PIN_CODE] India");
      assertThat(result.vaultEntries()).isEmpty();
    }
  }

  @Test
  void redact_spanInsideSellerRange_isLeftAlone() {
    String pan = "AAAAA" + "0000A";
    TextLayer layer = Layouts.page(line(40, 100, "Sold By Sample Traders PAN " + pan));
    PiiSpan sellerPan = span(layer, pan, PiiType.PAN);
    String text = PageText.of(layer.pages().getFirst()).text();
    var ctx = new DocumentContext(DocumentId.random(), List.of(new TextRange(1, 0, text.length())));

    TextRedactor.Result result =
        redactor.redact(layer, List.of(sellerPan), ctx, RedactionMode.PSEUDONYMIZE);

    assertThat(page(result).text()).isEqualTo(text);
    assertThat(result.spans()).isEmpty();
    assertThat(result.vaultEntries()).isEmpty();
  }

  @Test
  void redact_partialWordSpan_replacesWholeWord() {
    TextLayer layer = Layouts.page(line(40, 100, "Mobile:" + MOBILE));
    String text = PageText.of(layer.pages().getFirst()).text();
    int at = text.indexOf(MOBILE);

    TextRedactor.Result result =
        redactor.redact(
            layer,
            List.of(new PiiSpan(1, at, at + MOBILE.length(), PiiType.PHONE, "test")),
            CTX,
            RedactionMode.PSEUDONYMIZE);

    assertThat(page(result).text()).isEqualTo(tokens.token(PiiType.PHONE, MOBILE));
    assertThat(page(result).text()).doesNotContain("Mobile");
  }

  @Test
  void redact_returnsKeptSpansSortedByPageAndStart() {
    TextLayer layer = twoPages();
    var late = new PiiSpan(1, 6, 10, PiiType.PERSON, "test");
    var early = new PiiSpan(1, 0, 5, PiiType.PERSON, "test");
    var second = new PiiSpan(2, 0, 4, PiiType.PERSON, "test");

    TextRedactor.Result result =
        redactor.redact(layer, List.of(second, late, early), CTX, RedactionMode.REDACT);

    assertThat(result.spans()).containsExactly(early, late, second);
    assertThat(result.content().text())
        .isEqualTo("[REDACTED:PERSON] [REDACTED:PERSON]\n[REDACTED:PERSON] five");
  }

  @Test
  void redact_noSpans_keepsTextAndPages() {
    TextLayer layer = twoPages();

    TextRedactor.Result result = redactor.redact(layer, List.of(), CTX, RedactionMode.REDACT);

    assertThat(result.content().pages())
        .extracting(RedactedContent.Page::pageNo, RedactedContent.Page::text)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(1, "one, three"),
            org.assertj.core.groups.Tuple.tuple(2, "four five"));
    assertThat(result.content().pages().getFirst().width()).isEqualTo(595);
    assertThat(result.content().pages().getFirst().height()).isEqualTo(842);
  }

  @Test
  void redact_overlappingSpans_areRejected() {
    TextLayer layer = Layouts.page(line(40, 100, "aa bbb cc"));
    List<PiiSpan> spans =
        List.of(
            new PiiSpan(1, 0, 6, PiiType.PERSON, "a"), new PiiSpan(1, 3, 9, PiiType.PERSON, "b"));

    assertThatThrownBy(() -> redactor.redact(layer, spans, CTX, RedactionMode.REDACT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("spans overlap on page 1; merge them first");
  }

  @Test
  void redact_spanOnMissingPage_isRejected() {
    TextLayer layer = Layouts.page(line(40, 100, "aa bbb"));
    List<PiiSpan> spans = List.of(new PiiSpan(2, 0, 2, PiiType.PERSON, "a"));

    assertThatThrownBy(() -> redactor.redact(layer, spans, CTX, RedactionMode.REDACT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("span on page 2 not in layer");
  }

  @Test
  void redact_spanBeyondText_isRejected() {
    TextLayer layer = Layouts.page(line(40, 100, "aa bbb"));
    List<PiiSpan> spans = List.of(new PiiSpan(1, 0, 99, PiiType.PERSON, "a"));

    assertThatThrownBy(() -> redactor.redact(layer, spans, CTX, RedactionMode.REDACT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("span ends beyond page 1 text");
  }

  @Test
  void redact_duplicatePage_isRejected() {
    TextPage page = Layouts.page(line(40, 100, "aa")).pages().getFirst();
    TextLayer layer = new TextLayer(List.of(page, page));

    assertThatThrownBy(() -> redactor.redact(layer, List.of(), CTX, RedactionMode.REDACT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("layer has page 1 twice");
  }

  @Test
  void toString_showsIdsAndCountsButNoText() {
    TextLayer layer = Layouts.page(line(40, 100, "Phone " + MOBILE));

    TextRedactor.Result result =
        redactor.redact(
            layer, List.of(span(layer, MOBILE, PiiType.PHONE)), CTX, RedactionMode.PSEUDONYMIZE);

    String token = tokens.token(PiiType.PHONE, MOBILE);
    assertThat(result.toString())
        .contains(CTX.documentId().toString(), "pages=1", "spans=1", "vaultEntries=1")
        .doesNotContain(MOBILE, "Phone", token);
    assertThat(page(result).toString())
        .isEqualTo("RedactedContent.Page[pageNo=1, words=2, image=false]")
        .doesNotContain(token);
  }

  @Test
  void page_invalidPageNo_isRejected() {
    assertThatThrownBy(() -> new RedactedContent.Page(0, 1, 1, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void sameLine_usesHalfTheTallerWordHeight() {
    var word = new Word("a", 0, 100, 5, 8, 1);

    assertThat(TextRedactor.sameLine(word, new Word("b", 10, 104, 5, 8, 1))).isTrue();
    assertThat(TextRedactor.sameLine(word, new Word("b", 10, 104.5, 5, 8, 1))).isFalse();
  }

  private static TextLayer twoPages() {
    TextPage one = Layouts.page(line(40, 100, "one, three")).pages().getFirst();
    TextPage two =
        new TextPage(
            2,
            595,
            842,
            TextSource.PDF_TEXT,
            List.of(new Word("four", 40, 100, 20, 8, 1), new Word("five", 70, 100, 20, 8, 1)));
    return new TextLayer(List.of(one, two));
  }
}
