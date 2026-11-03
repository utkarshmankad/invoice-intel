package dev.invoiceintel.eval.run;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Copies the synthetic label fixtures ({@code fixtures/labels/}) into a dataset folder. */
public final class SyntheticDataset {

  /** A tax invoice with two lines and five PII span types. */
  public static final String TAX_INVOICE =
      "f1d081e43a5f16b64fb28a789949a92830ee7cc7bd00a7850a07317b282efc18";

  /** A bill of supply with no tax lines and no spans. */
  public static final String BILL_OF_SUPPLY =
      "18cd4a50fa69ff7d2d38ca3310f6f9dc420d1fcc4202e115c3f315c6d030fe0e";

  public static final List<String> ALL = List.of(BILL_OF_SUPPLY, TAX_INVOICE);

  private SyntheticDataset() {}

  /** Writes every fixture label into {@code dir} (created if needed) and returns it. */
  public static Path write(Path dir) {
    try {
      Files.createDirectories(dir);
      for (String sha : ALL) {
        try (InputStream in =
            SyntheticDataset.class.getResourceAsStream("/fixtures/labels/" + sha + ".json")) {
          if (in == null) {
            throw new IllegalStateException("fixture missing: " + sha);
          }
          Files.write(dir.resolve(sha + ".json"), in.readAllBytes());
        }
      }
      return dir;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
