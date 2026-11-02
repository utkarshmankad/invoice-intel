package dev.invoiceintel.eval.label;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Synthetic label files in {@code fixtures/labels/}. Each one's {@code sha256} is the hash of
 * {@link #originalOf(String) a short synthetic text}, so tests can write a matching "original".
 */
final class LabelFixtures {

  static final JsonMapper JSON = JsonMapper.builder().build();

  /** A tax invoice with two lines (one shipping) and spans of five PII types. */
  static final String TAX_INVOICE =
      "f1d081e43a5f16b64fb28a789949a92830ee7cc7bd00a7850a07317b282efc18.json";

  /** A bill of supply: optional fields null, no tax lines, no spans. */
  static final String BILL_OF_SUPPLY =
      "18cd4a50fa69ff7d2d38ca3310f6f9dc420d1fcc4202e115c3f315c6d030fe0e.json";

  static final List<String> ALL = List.of(TAX_INVOICE, BILL_OF_SUPPLY);

  private LabelFixtures() {}

  static String text(String name) {
    try (InputStream in = LabelFixtures.class.getResourceAsStream("/fixtures/labels/" + name)) {
      if (in == null) {
        throw new IllegalStateException("fixture missing: " + name);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static ObjectNode tree(String name) {
    return (ObjectNode) JSON.readTree(text(name));
  }

  /** The bytes whose SHA-256 the fixture carries. */
  static byte[] originalOf(String name) {
    int n = ALL.indexOf(name) + 1;
    return ("synthetic label fixture " + n).getBytes(StandardCharsets.US_ASCII);
  }
}
