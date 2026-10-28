package dev.invoiceintel.ingest.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class Sha256Test {

  @Test
  void hex_knownInput_matchesReferenceDigest() {
    assertThat(Sha256.hex("abc".getBytes(StandardCharsets.US_ASCII)))
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }

  @Test
  void hex_emptyInput_isLowerCase64Chars() {
    assertThat(Sha256.hex(new byte[0])).matches("[0-9a-f]{64}");
  }
}
