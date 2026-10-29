package dev.invoiceintel.extract;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code invoice-intel.llm.*} (architecture.md §10). Lives in {@code extract}, the module that uses
 * it, because {@code app} may not depend on {@code extract} (§4.1); start-up binds it once {@code
 * extract} is on the app's classpath (M3.8). No models is valid until then.
 */
@Validated
@ConfigurationProperties("invoice-intel.llm")
public record LlmProperties(List<@Valid ModelSpec> models) {

  public LlmProperties {
    models = models == null ? List.of() : List.copyOf(models);
  }

  /**
   * @throws IllegalArgumentException if two models share a name
   */
  public ModelRegistry registry() {
    return new ModelRegistry(models);
  }
}
