package dev.invoiceintel.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class LlmPropertiesTest {

  private static ValidatorFactory factory;
  private static Validator validator;

  @BeforeAll
  static void setUpValidator() {
    factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @AfterAll
  static void closeValidator() {
    factory.close();
  }

  /** A cloud and a local provider, configured the way application.yaml would (EXT-3). */
  private static Map<String, String> twoProviders() {
    Map<String, String> config = new HashMap<>();
    config.put("invoice-intel.llm.models[0].name", "cloud-vision");
    config.put("invoice-intel.llm.models[0].provider", "openai");
    config.put("invoice-intel.llm.models[0].model-id", "example-vision-1");
    config.put("invoice-intel.llm.models[0].capabilities", "text,vision");
    config.put("invoice-intel.llm.models[0].input-price-per-million", "2.50");
    config.put("invoice-intel.llm.models[0].output-price-per-million", "10.00");
    config.put("invoice-intel.llm.models[1].name", "local-text");
    config.put("invoice-intel.llm.models[1].provider", "ollama");
    config.put("invoice-intel.llm.models[1].model-id", "example-local-8b");
    config.put("invoice-intel.llm.models[1].capabilities", "text");
    config.put("invoice-intel.llm.models[1].input-price-per-million", "0");
    config.put("invoice-intel.llm.models[1].output-price-per-million", "0");
    return config;
  }

  private static LlmProperties bind(Map<String, String> config) {
    return new Binder(new MapConfigurationPropertySource(config))
        .bindOrCreate("invoice-intel.llm", LlmProperties.class);
  }

  @Test
  void bind_twoProviders_configuresBothWithoutCode() {
    LlmProperties properties = bind(twoProviders());

    assertThat(validator.validate(properties)).isEmpty();
    ModelRegistry registry = properties.registry();
    assertThat(registry.models())
        .extracting(ModelSpec::name, ModelSpec::provider, ModelSpec::modelId)
        .containsExactly(
            tuple("cloud-vision", "openai", "example-vision-1"),
            tuple("local-text", "ollama", "example-local-8b"));
    ModelSpec cloud = registry.find("cloud-vision").orElseThrow();
    assertThat(cloud.capabilities())
        .containsExactlyInAnyOrder(ModelCapability.TEXT, ModelCapability.VISION);
    assertThat(cloud.inputPricePerMillion()).isEqualByComparingTo("2.50");
    assertThat(cloud.outputPricePerMillion()).isEqualByComparingTo("10");
    assertThat(registry.find("local-text").orElseThrow().inputPricePerMillion())
        .isEqualByComparingTo("0");
  }

  @Test
  void registry_supporting_filtersByCapabilityInConfigOrder() {
    ModelRegistry registry = bind(twoProviders()).registry();

    assertThat(registry.supporting(ModelCapability.TEXT))
        .extracting(ModelSpec::name)
        .containsExactly("cloud-vision", "local-text");
    assertThat(registry.supporting(ModelCapability.VISION))
        .extracting(ModelSpec::name)
        .containsExactly("cloud-vision");
  }

  @Test
  void registry_find_unknownName_isEmpty() {
    assertThat(bind(twoProviders()).registry().find("missing")).isEmpty();
  }

  @Test
  void bind_noModels_isEmptyAndValid() {
    LlmProperties properties = bind(Map.of());

    assertThat(properties.models()).isEmpty();
    assertThat(validator.validate(properties)).isEmpty();
    assertThat(properties.registry().models()).isEmpty();
  }

  @Test
  void bind_unknownCapability_fails() {
    Map<String, String> config = twoProviders();
    config.put("invoice-intel.llm.models[1].capabilities", "audio");

    assertThatThrownBy(() -> bind(config)).isInstanceOf(BindException.class);
  }

  @Test
  void validate_missingOrNegativeValues_areReported() {
    Map<String, String> config = twoProviders();
    config.remove("invoice-intel.llm.models[1].provider");
    config.put("invoice-intel.llm.models[1].model-id", " ");
    config.remove("invoice-intel.llm.models[1].capabilities");
    config.put("invoice-intel.llm.models[1].input-price-per-million", "-0.01");
    config.remove("invoice-intel.llm.models[1].output-price-per-million");

    assertThat(paths(validator.validate(bind(config))))
        .containsExactlyInAnyOrder(
            "models[1].provider",
            "models[1].modelId",
            "models[1].capabilities",
            "models[1].inputPricePerMillion",
            "models[1].outputPricePerMillion");
  }

  @Test
  void registry_duplicateNames_areRejected() {
    Map<String, String> config = twoProviders();
    config.put("invoice-intel.llm.models[1].name", "cloud-vision");
    LlmProperties properties = bind(config);

    assertThatThrownBy(properties::registry)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cloud-vision");
  }

  @Test
  void modelSpec_capabilitiesAreCopied() {
    Set<ModelCapability> capabilities = new HashSet<>(Set.of(ModelCapability.TEXT));
    ModelSpec spec = new ModelSpec("m", "p", "id", capabilities, BigDecimal.ZERO, BigDecimal.ZERO);
    capabilities.add(ModelCapability.VISION);

    assertThat(spec.supports(ModelCapability.VISION)).isFalse();
    assertThat(
            new ModelSpec("m", "p", "id", null, BigDecimal.ZERO, BigDecimal.ZERO)
                .supports(ModelCapability.TEXT))
        .isFalse();
    assertThat(new LlmProperties(null, null, null, null, null, null).models()).isEqualTo(List.of());
  }

  private static Set<String> paths(Set<? extends ConstraintViolation<?>> violations) {
    return violations.stream().map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet());
  }

  @Test
  void bind_concurrencyCaps_buildRateLimiter() {
    Map<String, String> config = twoProviders();
    config.put("invoice-intel.llm.max-concurrent-calls.openai", "2");
    config.put("invoice-intel.llm.default-max-concurrent-calls", "1");

    LlmProperties properties = bind(config);

    assertThat(validator.validate(properties)).isEmpty();
    ProviderRateLimiter limiter = properties.rateLimiter();
    assertThat(limiter.available("openai")).isEqualTo(2);
    assertThat(limiter.available("ollama")).isEqualTo(1);
  }

  @Test
  void bind_noConcurrencyCaps_usesDefault() {
    assertThat(bind(twoProviders()).rateLimiter().available("openai"))
        .isEqualTo(LlmProperties.DEFAULT_MAX_CONCURRENT_CALLS);
  }

  @Test
  void bind_extractionAndProviders_readsModelStrategyAndEndpoints() {
    Map<String, String> config = twoProviders();
    config.put("invoice-intel.llm.extraction.model", "cloud-vision");
    config.put("invoice-intel.llm.extraction.strategy", "hybrid");
    config.put("invoice-intel.llm.ollama.base-url", "http://127.0.0.1:9999");
    config.put("invoice-intel.llm.anthropic.api-key", "test-key-not-real");
    config.put("invoice-intel.llm.anthropic.max-tokens", "2048");
    config.put("invoice-intel.llm.anthropic.timeout", "30s");

    LlmProperties properties = bind(config);

    assertThat(properties.extraction().enabled()).isTrue();
    assertThat(properties.extraction().model()).isEqualTo("cloud-vision");
    assertThat(properties.extraction().strategy()).isEqualTo(ExtractionStrategy.HYBRID);
    assertThat(properties.ollama().baseUrl()).isEqualTo("http://127.0.0.1:9999");
    assertThat(properties.anthropic().apiKey()).isEqualTo("test-key-not-real");
    assertThat(properties.anthropic().maxTokens()).isEqualTo(2048);
    assertThat(properties.anthropic().timeout()).isEqualTo(java.time.Duration.ofSeconds(30));
  }
}
