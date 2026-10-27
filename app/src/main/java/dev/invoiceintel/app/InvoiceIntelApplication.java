package dev.invoiceintel.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Entry point of the Invoice Intel service. */
@SpringBootApplication(scanBasePackages = "dev.invoiceintel")
@ConfigurationPropertiesScan("dev.invoiceintel")
public class InvoiceIntelApplication {

  public static void main(String[] args) {
    SpringApplication.run(InvoiceIntelApplication.class, args);
  }
}
