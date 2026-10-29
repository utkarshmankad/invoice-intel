package dev.invoiceintel.extract;

import java.util.Objects;

/**
 * A versioned prompt (EXT-5, architecture.md §6.5): stored on every {@code llm_call} as {@link
 * #id()}.
 *
 * @param name prompt folder under {@code prompts/}
 * @param version file {@code v{version}.st}
 */
public record PromptRef(String name, int version) {

  public PromptRef {
    Objects.requireNonNull(name, "name");
    if (name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    if (version < 1) {
      throw new IllegalArgumentException("version must be >= 1");
    }
  }

  /** {@code name/v{version}}, the {@code llm_call.prompt_version} value. */
  public String id() {
    return name + "/v" + version;
  }
}
