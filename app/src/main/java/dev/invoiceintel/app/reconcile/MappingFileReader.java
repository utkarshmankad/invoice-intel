package dev.invoiceintel.app.reconcile;

import dev.invoiceintel.reconcile.OrderHistoryField;
import dev.invoiceintel.reconcile.OrderHistoryMapping;
import dev.invoiceintel.reconcile.OrderHistoryMappings;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Reads {@code config/order-history-mapping.yml} (plan D2, REC-1): a {@code files} list, each with
 * {@code file}, {@code kind} and {@code columns} of {@code name} and {@code target} (an {@link
 * OrderHistoryField} target name, or null for a column that isn't imported).
 */
public final class MappingFileReader {

  private MappingFileReader() {}

  /**
   * @throws MappingFileException if the file is missing or doesn't have that shape
   */
  public static OrderHistoryMappings read(Path file) {
    if (file == null) {
      throw new MappingFileException("invoice-intel.order-history.mapping-file is not set");
    }
    Object root;
    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      root = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
    } catch (IOException | YAMLException e) {
      throw new MappingFileException("order-history mapping file can't be read", e);
    }
    try {
      return parse(root);
    } catch (IllegalArgumentException e) {
      throw new MappingFileException("order-history mapping file is invalid: " + e.getMessage(), e);
    }
  }

  static OrderHistoryMappings parse(Object root) {
    var files = new ArrayList<OrderHistoryMapping>();
    for (Object entry : list(map(root).get("files"), "files")) {
      var file = map(entry);
      var columns = new LinkedHashMap<String, OrderHistoryField>();
      for (Object column : list(file.get("columns"), "columns")) {
        var c = map(column);
        var name = string(c.get("name"), "name");
        var target = c.get("target");
        var field =
            target == null
                ? null
                : OrderHistoryField.fromTarget(string(target, "target"))
                    .orElseThrow(() -> new IllegalArgumentException("unknown target " + target));
        if (columns.containsKey(name)) {
          throw new IllegalArgumentException("column listed twice in " + file.get("file"));
        }
        columns.put(name, field);
      }
      files.add(
          new OrderHistoryMapping(
              string(file.get("file"), "file"), string(file.get("kind"), "kind"), columns));
    }
    return new OrderHistoryMappings(files);
  }

  private static Map<?, ?> map(Object value) {
    if (value instanceof Map<?, ?> m) {
      return m;
    }
    throw new IllegalArgumentException("expected a mapping");
  }

  private static List<?> list(Object value, String key) {
    if (value instanceof List<?> l) {
      return l;
    }
    throw new IllegalArgumentException("expected a list under " + key);
  }

  private static String string(Object value, String key) {
    if (value instanceof String s && !s.isBlank()) {
      return s;
    }
    throw new IllegalArgumentException("expected text under " + key);
  }
}
