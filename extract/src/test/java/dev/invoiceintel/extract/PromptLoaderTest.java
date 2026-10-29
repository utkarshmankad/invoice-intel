package dev.invoiceintel.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Versioned prompt files (EXT-5, M3.4). */
class PromptLoaderTest {

  private final PromptLoader loader = new PromptLoader();

  @Test
  void render_invoiceExtractionV1_explainsLineMarkers() {
    PromptLoader.Rendered rendered =
        loader.render(new PromptRef("invoice-extraction", 1), Map.of());

    assertThat(rendered.ref().id()).isEqualTo("invoice-extraction/v1");
    assertThat(rendered.text())
        .startsWith("You extract fields")
        .contains("p<page>:l<line>")
        .contains("lineRefs")
        .doesNotEndWith("\n");
  }

  @Test
  void render_variable_isSubstituted() {
    assertThat(loader.render(new PromptRef("totals", 1), Map.of("currency", "INR")).text())
        .isEqualTo("Extract the totals for INR.");
  }

  @Test
  void render_missingVariable_fails() {
    assertThatThrownBy(() -> loader.render(new PromptRef("totals", 1), Map.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("currency");
  }

  @Test
  void request_recordsPromptRef() {
    ExtractionRequest<InvoiceExtraction> request =
        loader.request(
            "local-text",
            new PromptRef("invoice-extraction", 1),
            Map.of(),
            InvoiceExtraction.class);

    assertThat(request.prompt().id()).isEqualTo("invoice-extraction/v1");
    assertThat(request.model()).isEqualTo("local-text");
    assertThat(request.instructions()).startsWith("You extract fields");
    assertThat(request.outputType()).isEqualTo(InvoiceExtraction.class);
  }

  @Test
  void template_repeatedLoad_returnsCachedText() {
    var ref = new PromptRef("totals", 1);

    assertThat(loader.template(ref)).isSameAs(loader.template(ref));
  }

  @Test
  void template_unknownVersion_fails() {
    assertThatThrownBy(() -> loader.template(new PromptRef("invoice-extraction", 99)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("no prompt file for invoice-extraction/v99");
  }

  @ParameterizedTest
  @ValueSource(strings = {"../secrets", "Invoice", "a/b", "a.b", "-x"})
  void template_nameOutsidePromptFolders_fails(String name) {
    assertThatThrownBy(() -> loader.template(new PromptRef(name, 1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith("invalid prompt name");
  }

  @Test
  void rendered_toString_hidesText() {
    var rendered = new PromptLoader.Rendered(new PromptRef("totals", 1), "secret text");

    assertThat(rendered.toString()).isEqualTo("PromptLoader.Rendered[totals/v1]");
  }
}
