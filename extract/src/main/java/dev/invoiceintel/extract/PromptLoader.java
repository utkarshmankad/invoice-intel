package dev.invoiceintel.extract;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.ai.template.st.StTemplateRenderer;

/**
 * Loads versioned prompt files from the classpath (EXT-5, architecture.md §6.5): {@code
 * prompts/{name}/v{version}.st}, StringTemplate with {@code {variable}} placeholders. A prompt
 * changes by adding a new version file, never by editing an old one, so a {@link PromptRef} always
 * names the same text. Rendering fails on a missing variable.
 */
public final class PromptLoader {

  static final String ROOT = "prompts/";
  // Prompt names are folder names: no separators or dots, so a ref can't leave prompts/.
  private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]*");

  private final ClassLoader classLoader;
  private final StTemplateRenderer renderer = StTemplateRenderer.builder().build();
  private final Map<PromptRef, String> templates = new ConcurrentHashMap<>();

  public PromptLoader() {
    this(PromptLoader.class.getClassLoader());
  }

  PromptLoader(ClassLoader classLoader) {
    this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
  }

  /** A rendered prompt and the ref it came from, ready for {@link ExtractionRequest}. */
  public record Rendered(PromptRef ref, String text) {

    public Rendered {
      Objects.requireNonNull(ref, "ref");
      Objects.requireNonNull(text, "text");
    }

    /** Never prints the text. */
    @Override
    public String toString() {
      return "PromptLoader.Rendered[" + ref.id() + "]";
    }
  }

  /** The raw template of {@code ref}. */
  public String template(PromptRef ref) {
    Objects.requireNonNull(ref, "ref");
    if (!NAME.matcher(ref.name()).matches()) {
      throw new IllegalArgumentException("invalid prompt name: " + ref.name());
    }
    return templates.computeIfAbsent(ref, this::read);
  }

  /** {@code ref} rendered with {@code variables}. */
  public Rendered render(PromptRef ref, Map<String, Object> variables) {
    Objects.requireNonNull(variables, "variables");
    return new Rendered(ref, renderer.apply(template(ref), variables).strip());
  }

  /**
   * A request for {@code model} with {@code ref} rendered as its instructions; the call records
   * {@code ref} (EXT-4).
   */
  public <T> ExtractionRequest<T> request(
      String model, PromptRef ref, Map<String, Object> variables, Class<T> outputType) {
    Rendered rendered = render(ref, variables);
    return new ExtractionRequest<>(model, rendered.ref(), rendered.text(), outputType);
  }

  private String read(PromptRef ref) {
    String path = ROOT + ref.name() + "/v" + ref.version() + ".st";
    try (InputStream in = classLoader.getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalArgumentException("no prompt file for " + ref.id());
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read prompt " + ref.id(), e);
    }
  }
}
