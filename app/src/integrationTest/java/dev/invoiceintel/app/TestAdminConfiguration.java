package dev.invoiceintel.app;

import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/** A throwaway admin password per test JVM, so none is stored in config files. */
@TestConfiguration(proxyBeanMethods = false)
public class TestAdminConfiguration {

  public static final String ADMIN_USERNAME = "admin";
  public static final String ADMIN_PASSWORD = UUID.randomUUID().toString();

  @Bean
  DynamicPropertyRegistrar adminCredentials() {
    return registry -> {
      registry.add("invoice-intel.security.admin-username", () -> ADMIN_USERNAME);
      registry.add("invoice-intel.security.admin-password", () -> ADMIN_PASSWORD);
    };
  }
}
