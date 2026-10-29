package dev.invoiceintel.extract;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** The configured models, looked up by name or capability (EXT-3, architecture.md §6.5). */
public final class ModelRegistry {

  private final List<ModelSpec> models;

  /**
   * @throws IllegalArgumentException if two models share a name
   */
  public ModelRegistry(List<ModelSpec> models) {
    this.models = List.copyOf(Objects.requireNonNull(models, "models"));
    Set<String> names = new HashSet<>();
    for (ModelSpec model : this.models) {
      if (!names.add(model.name())) {
        throw new IllegalArgumentException("duplicate model name: " + model.name());
      }
    }
  }

  /** All models in config order. */
  public List<ModelSpec> models() {
    return models;
  }

  public Optional<ModelSpec> find(String name) {
    return models.stream().filter(model -> model.name().equals(name)).findFirst();
  }

  /** Models that accept {@code capability}, in config order. */
  public List<ModelSpec> supporting(ModelCapability capability) {
    return models.stream().filter(model -> model.supports(capability)).toList();
  }
}
