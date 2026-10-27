package dev.invoiceintel.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest
class InvoiceIntelApplicationIT {

  @Autowired ApplicationContext context;

  @Test
  void contextLoads_withApplicationBean() {
    assertThat(context.getBean(InvoiceIntelApplication.class)).isNotNull();
  }
}
