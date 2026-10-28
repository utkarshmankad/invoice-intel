package dev.invoiceintel.pii;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * NER model directories the build downloaded (pii/build.gradle.kts, ADR-15), from the system
 * property {@code invoiceintel.ner.models} ({@code name=dir,name=dir}). The first is the model the
 * detector uses; {@code nerBenchmark} passes the other candidate too.
 */
final class NerModels {

  static final String PROPERTY = "invoiceintel.ner.models";

  private NerModels() {}

  static Map<String, Path> all() {
    String value = System.getProperty(PROPERTY);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(PROPERTY + " is not set; run the tests through Gradle");
    }
    var models = new LinkedHashMap<String, Path>();
    for (String entry : value.split(",")) {
      int eq = entry.indexOf('=');
      models.put(entry.substring(0, eq), Path.of(entry.substring(eq + 1)));
    }
    return models;
  }

  static Map.Entry<String, Path> chosen() {
    return all().entrySet().iterator().next();
  }
}
