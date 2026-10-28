package dev.invoiceintel.pii;

import com.google.crypto.tink.Mac;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Deterministic pseudonyms for PII values: {@code PREFIX + "_" + base32(HMAC-SHA256(tokenKey,
 * normalized(value)))[0..8]} (PII-3, ADR-06, architecture.md §6.3). The prefix is the type name,
 * except {@code ID} for PAN and Aadhaar (spec §6.1). The same value gives the same token in every
 * document, so order numbers stay joinable for reconciliation.
 *
 * <p>Build it with {@link PiiKeys#tokenGenerator}, which only accepts an HMAC-SHA256 keyset without
 * an output prefix.
 */
public final class TokenGenerator {

  private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  /** 8 base32 characters carry the first 5 bytes (40 bits) of the MAC. */
  static final int TOKEN_CHARS = 8;

  private final Mac mac;

  TokenGenerator(Mac mac) {
    this.mac = Objects.requireNonNull(mac, "mac");
  }

  /** The token for {@code value}, e.g. {@code PHONE_K3QZ7F2A}. */
  public String token(PiiType type, String value) {
    Objects.requireNonNull(type, "type");
    byte[] tag;
    try {
      tag = mac.computeMac(normalize(value).getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      // The message names neither the value nor the key (PII-7, PII-8).
      throw new IllegalStateException("token MAC failed", e);
    }
    return prefix(type) + "_" + base32Prefix(tag);
  }

  /** Spec §6.1: buyer PAN and Aadhaar share the {@code ID} prefix; other types use their name. */
  static String prefix(PiiType type) {
    return switch (type) {
      case PAN, AADHAAR -> "ID";
      default -> type.name();
    };
  }

  /**
   * Whitespace runs collapsed to one space, trimmed, ASCII letters upper-cased. OCR and layout
   * differences in spacing and case then don't split one value into several tokens. Unicode case
   * mapping and NFKC are left out on purpose: FindSecBugs reports them (IMPROPER_UNICODE) and the
   * identifiers that must join across documents are ASCII.
   */
  static String normalize(String value) {
    String collapsed = WHITESPACE.matcher(Objects.requireNonNull(value, "value")).replaceAll(" ");
    StringBuilder out = new StringBuilder(collapsed.length());
    for (char c : collapsed.strip().toCharArray()) {
      out.append(c >= 'a' && c <= 'z' ? (char) (c - ('a' - 'A')) : c);
    }
    return out.toString();
  }

  /** RFC 4648 base32 of the first 5 bytes: exactly {@value #TOKEN_CHARS} characters. */
  static String base32Prefix(byte[] bytes) {
    long bits = 0;
    for (int i = 0; i < 5; i++) {
      bits = (bits << 8) | (bytes[i] & 0xFF);
    }
    char[] out = new char[TOKEN_CHARS];
    for (int i = TOKEN_CHARS - 1; i >= 0; i--) {
      out[i] = BASE32[(int) (bits & 0x1F)];
      bits >>>= 5;
    }
    return new String(out);
  }
}
