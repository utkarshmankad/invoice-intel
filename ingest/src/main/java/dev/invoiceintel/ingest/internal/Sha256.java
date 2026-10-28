package dev.invoiceintel.ingest.internal;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Lower-case hex SHA-256, the dedupe key of {@code document.sha256} (ING-2). */
public final class Sha256 {

  private Sha256() {}

  public static String hex(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException e) {
      // Every Java platform must provide SHA-256.
      throw new IllegalStateException(e);
    }
  }
}
