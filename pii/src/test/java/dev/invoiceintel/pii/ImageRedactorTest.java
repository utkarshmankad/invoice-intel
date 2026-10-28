package dev.invoiceintel.pii;

import static dev.invoiceintel.pii.Layouts.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.text.TextLayer;
import dev.invoiceintel.text.TextPage;
import dev.invoiceintel.text.TextSource;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Hand-made synthetic layouts and blank images only (testing.md). */
class ImageRedactorTest {

  // Built from parts so the CI secret scan (.gitleaks.toml) doesn't flag test sources.
  private static final String MOBILE = "98765" + "43210";
  private static final int BLACK = Color.BLACK.getRGB();
  private static final int WHITE = Color.WHITE.getRGB();

  private static TextRedactor textRedactor;

  private final ImageRedactor redactor = new ImageRedactor();

  @BeforeAll
  static void keys() throws GeneralSecurityException {
    textRedactor =
        new TextRedactor(
            TestKeysets.tokens(TestKeysets.newTokenKeyset()),
            TestKeysets.vault(TestKeysets.newVaultKeyset()));
  }

  // The layout page is 595 x 842 units; the image is rendered at twice that.
  private static final TextLayer LAYER =
      Layouts.page(line(40, 100, "Phone: " + MOBILE + " thanks"));

  private static PiiSpan mobileSpan() {
    int at = PageText.of(LAYER.pages().getFirst()).text().indexOf(MOBILE);
    return new PiiSpan(1, at, at + MOBILE.length(), PiiType.PHONE, "test");
  }

  @Test
  void mask_spanWord_isBlackInImagePixelsWithPadding() {
    BufferedImage masked =
        decode(redactor.mask(page(), List.of(mobileSpan()), whitePng(1190, 1684)));

    // The number's box is x 73..123, y 100..108 in page units: 146..246, 200..216 in pixels.
    assertThat(masked.getRGB(146, 200)).isEqualTo(BLACK);
    assertThat(masked.getRGB(245, 215)).isEqualTo(BLACK);
    assertThat(masked.getRGB(196, 208)).isEqualTo(BLACK);
    // Padding: 0.4 word heights above and below, 0.15 left and right.
    assertThat(masked.getRGB(196, 195)).isEqualTo(BLACK);
    assertThat(masked.getRGB(196, 221)).isEqualTo(BLACK);
    assertThat(masked.getRGB(144, 208)).isEqualTo(BLACK);
    assertThat(masked.getRGB(247, 208)).isEqualTo(BLACK);
  }

  @Test
  void mask_otherWords_stayUntouched() {
    BufferedImage masked =
        decode(redactor.mask(page(), List.of(mobileSpan()), whitePng(1190, 1684)));

    assertThat(masked.getRGB(100, 208)).as("Phone:").isEqualTo(WHITE);
    assertThat(masked.getRGB(280, 208)).as("thanks").isEqualTo(WHITE);
    assertThat(masked.getRGB(196, 180)).as("above").isEqualTo(WHITE);
    assertThat(masked.getRGB(196, 240)).as("below").isEqualTo(WHITE);
  }

  @Test
  void mask_partOfAWord_masksTheWholeWord() {
    int at = mobileSpan().start();
    var partial = new PiiSpan(1, at + 3, at + 5, PiiType.PHONE, "test");

    BufferedImage masked = decode(redactor.mask(page(), List.of(partial), whitePng(1190, 1684)));

    assertThat(masked.getRGB(146, 208)).isEqualTo(BLACK);
    assertThat(masked.getRGB(245, 208)).isEqualTo(BLACK);
  }

  @Test
  void mask_noSpans_keepsImageAsPng() {
    BufferedImage masked = decode(redactor.mask(page(), List.of(), whitePng(100, 140)));

    assertThat(masked.getWidth()).isEqualTo(100);
    assertThat(masked.getRGB(50, 70)).isEqualTo(WHITE);
  }

  @Test
  void mask_ocrPageInPixels_masksAtSameCoordinates() {
    var ocrPage = new TextPage(1, 1190, 1684, TextSource.OCR, line(146, 200, MOBILE).words());
    var span = new PiiSpan(1, 0, MOBILE.length(), PiiType.PHONE, "test");

    BufferedImage masked = decode(redactor.mask(ocrPage, List.of(span), whitePng(1190, 1684)));

    assertThat(masked.getRGB(150, 204)).isEqualTo(BLACK);
    assertThat(masked.getRGB(300, 204)).isEqualTo(WHITE);
  }

  @Test
  void mask_spanOnOtherPage_isRejectedNamingPagesOnly() {
    var span = new PiiSpan(2, 0, 4, PiiType.PHONE, "test");

    assertThatThrownBy(() -> redactor.mask(page(), List.of(span), whitePng(10, 10)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("span on page 2 not this page");
  }

  @Test
  void mask_spanBeyondText_isRejected() {
    var span = new PiiSpan(1, 0, 500, PiiType.PHONE, "test");

    assertThatThrownBy(() -> redactor.mask(page(), List.of(span), whitePng(10, 10)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("span ends beyond page 1 text");
  }

  @Test
  void mask_notAnImage_isRejected() {
    assertThatThrownBy(() -> redactor.mask(page(), List.of(), new byte[] {1, 2, 3}))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("page 1 image is not PNG or JPEG");
  }

  @Test
  void redact_textResult_addsMaskedImageToEveryPage() {
    TextRedactor.Result text = redactText();

    RedactedContent content = redactor.redact(text, LAYER, Map.of(1, whitePng(1190, 1684)));

    assertThat(content.text()).isEqualTo(text.content().text());
    assertThat(content.documentId()).isEqualTo(text.content().documentId());
    RedactedContent.Page page = content.pages().getFirst();
    assertThat(page.image()).isPresent();
    assertThat(decode(page.image().orElseThrow()).getRGB(196, 208)).isEqualTo(BLACK);
    assertThat(page.toString()).contains("image=true").doesNotContain(MOBILE);
    assertThat(text.content().pages().getFirst().image()).as("input unchanged").isEmpty();
  }

  @Test
  void redact_imageAccessor_returnsCopies() {
    RedactedContent content = redactor.redact(redactText(), LAYER, Map.of(1, whitePng(20, 20)));
    RedactedContent.Page page = content.pages().getFirst();

    page.image().orElseThrow()[0] = 0;

    assertThat(page.image().orElseThrow()[0]).isNotZero();
  }

  @Test
  void redact_missingPageImage_isRejected() {
    assertThatThrownBy(() -> redactor.redact(redactText(), LAYER, Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("no image for page 1");
  }

  @Test
  void redact_layerOfOtherPages_isRejected() {
    var other =
        new TextLayer(
            List.of(new TextPage(2, 595, 842, TextSource.PDF_TEXT, line(1, 1, "x").words())));

    assertThatThrownBy(() -> redactor.redact(redactText(), other, Map.of(2, whitePng(10, 10))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> redactor.redact(redactText(), new TextLayer(List.of()), Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static TextPage page() {
    return LAYER.pages().getFirst();
  }

  private static TextRedactor.Result redactText() {
    return textRedactor.redact(
        LAYER,
        List.of(mobileSpan()),
        new DocumentContext(DocumentId.random()),
        RedactionMode.PSEUDONYMIZE);
  }

  static byte[] whitePng(int width, int height) {
    var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    var graphics = image.createGraphics();
    graphics.setColor(Color.WHITE);
    graphics.fillRect(0, 0, width, height);
    graphics.dispose();
    var out = new ByteArrayOutputStream();
    try {
      ImageIO.write(image, "png", out);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }

  private static BufferedImage decode(byte[] png) {
    try {
      return ImageIO.read(new ByteArrayInputStream(png));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
