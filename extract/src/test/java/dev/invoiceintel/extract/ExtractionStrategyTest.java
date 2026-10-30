package dev.invoiceintel.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.invoiceintel.common.extraction.InvoiceExtraction;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/** Strategies and the models that can run them (EXT-2, M3.5). */
class ExtractionStrategyTest {

  private static ModelSpec model(ModelCapability... capabilities) {
    return new ModelSpec(
        "m", "ollama", "example", Set.of(capabilities), BigDecimal.ZERO, BigDecimal.ZERO);
  }

  @ParameterizedTest
  @CsvSource({
    "TEXT, TEXT, true",
    "TEXT, VISION, false",
    "VISION, TEXT, false",
    "VISION, VISION, true",
    "HYBRID, TEXT, false",
    "HYBRID, VISION, false",
  })
  void supportedBy_singleCapabilityModel(
      ExtractionStrategy strategy, ModelCapability capability, boolean expected) {
    assertThat(strategy.supportedBy(model(capability))).isEqualTo(expected);
  }

  @ParameterizedTest
  @EnumSource(ExtractionStrategy.class)
  void supportedBy_textAndVisionModel_runsEveryStrategy(ExtractionStrategy strategy) {
    assertThat(strategy.supportedBy(model(ModelCapability.TEXT, ModelCapability.VISION))).isTrue();
  }

  @ParameterizedTest
  @EnumSource(ExtractionStrategy.class)
  void defaultPrompt_existsAndRenders(ExtractionStrategy strategy) {
    PromptLoader.Rendered rendered = new PromptLoader().render(strategy.defaultPrompt(), Map.of());

    assertThat(rendered.text()).startsWith("You extract fields").contains("evidence.page");
  }

  @Test
  void defaultPrompts_describeTheirInput() {
    var loader = new PromptLoader();

    assertThat(loader.template(ExtractionStrategy.VISION.defaultPrompt()))
        .contains("images")
        .doesNotContain("p<page>:l<line>");
    assertThat(loader.template(ExtractionStrategy.HYBRID.defaultPrompt()))
        .contains("images")
        .contains("p<page>:l<line>");
  }

  @Test
  void request_withoutStrategy_isText() {
    var request = new ExtractionRequest<>("m", new PromptRef("totals", 1), "x", String.class);

    assertThat(request.strategy()).isEqualTo(ExtractionStrategy.TEXT);
    assertThat(request.toString()).contains("strategy=TEXT");
  }

  @Test
  void request_nullStrategy_isRejected() {
    assertThatThrownBy(
            () -> new ExtractionRequest<>("m", new PromptRef("totals", 1), "x", String.class, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("strategy");
  }

  @Test
  void promptLoader_requestForStrategy_usesItsDefaultPrompt() {
    ExtractionRequest<InvoiceExtraction> request =
        new PromptLoader()
            .request("local-vision", ExtractionStrategy.VISION, Map.of(), InvoiceExtraction.class);

    assertThat(request.strategy()).isEqualTo(ExtractionStrategy.VISION);
    assertThat(request.prompt().id()).isEqualTo("invoice-extraction-vision/v1");
    assertThat(request.instructions()).contains("Never guess what a covered area held");
  }
}
