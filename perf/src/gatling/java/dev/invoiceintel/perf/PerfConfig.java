package dev.invoiceintel.perf;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/** Target, credentials, load profile and thresholds for the simulations (docs/ci.md §5). */
final class PerfConfig {

  private final Properties thresholds;
  private final String profile;

  private PerfConfig(Properties thresholds, String profile) {
    this.thresholds = thresholds;
    this.profile = profile;
  }

  static PerfConfig load() {
    Properties props = new Properties();
    try (InputStream in = PerfConfig.class.getResourceAsStream("/thresholds.conf")) {
      if (in == null) {
        throw new IllegalStateException("thresholds.conf not on the classpath");
      }
      props.load(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return new PerfConfig(props, System.getProperty("perf.profile", "local"));
  }

  String baseUrl() {
    return System.getProperty("perf.baseUrl", "http://127.0.0.1:8080");
  }

  String adminUser() {
    String user = System.getenv("APP_ADMIN_USER");
    return user == null || user.isBlank() ? "admin" : user;
  }

  String adminPassword() {
    String password = System.getenv("APP_ADMIN_PASSWORD");
    if (password == null || password.isBlank()) {
      throw new IllegalStateException("APP_ADMIN_PASSWORD must be set");
    }
    return password;
  }

  int users() {
    return intValue(profile + ".users");
  }

  int durationSeconds() {
    return intValue(profile + ".duration-seconds");
  }

  int intValue(String key) {
    String value = thresholds.getProperty(key);
    if (value == null) {
      throw new IllegalStateException("Missing " + key + " in thresholds.conf");
    }
    return Integer.parseInt(value.trim());
  }
}
