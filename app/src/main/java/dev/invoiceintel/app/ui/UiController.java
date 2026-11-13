package dev.invoiceintel.app.ui;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** The review UI's login page and home page (G6, architecture.md §6.8). */
@Controller
@SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification = "Inventory marker, not a defect; only /login is public (architecture.md §11)")
class UiController {

  @GetMapping("/login")
  String login() {
    return "login";
  }

  @GetMapping("/")
  String home() {
    return "home";
  }
}
