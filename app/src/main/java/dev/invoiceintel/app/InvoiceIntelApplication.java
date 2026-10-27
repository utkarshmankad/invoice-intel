package dev.invoiceintel.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point of the Invoice Intel service. */
@SpringBootApplication(scanBasePackages = "dev.invoiceintel")
public class InvoiceIntelApplication {

  public static void main(String[] args) {
    SpringApplication.run(InvoiceIntelApplication.class, args);
  }
}
