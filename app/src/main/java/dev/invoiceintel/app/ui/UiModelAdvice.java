package dev.invoiceintel.app.ui;

import java.security.Principal;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Model attributes every UI page's layout needs (architecture.md §6.8). */
@ControllerAdvice(basePackageClasses = UiModelAdvice.class)
class UiModelAdvice {

  /** Name shown in the navigation bar; empty on the login page. */
  @ModelAttribute("username")
  String username(Principal principal) {
    return principal == null ? "" : principal.getName();
  }
}
