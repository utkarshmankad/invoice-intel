package dev.invoiceintel.common.fixtures;

import java.util.List;

/** Fake buyer. Everything but the state counts as PII in this project (spec §6.1). */
public record Buyer(
    String name, String street, String pinCode, GstState state, String phone, String email) {

  /** Values a PII detector must find and redaction must remove. */
  public List<String> piiValues() {
    return List.of(name, street, pinCode, phone, email);
  }
}
