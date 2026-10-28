package dev.invoiceintel.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BlobKeyTest {

  @Test
  void new_validBucketAndKey_keepsBoth() {
    BlobKey key = new BlobKey("raw", "raw/0001/pages/1.png");

    assertThat(key.bucket()).isEqualTo("raw");
    assertThat(key.key()).isEqualTo("raw/0001/pages/1.png");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "ab", "Raw", "-raw", "raw-", "raw_bucket"})
  void new_invalidBucket_isRejected(String bucket) {
    assertThatIllegalArgumentException().isThrownBy(() -> new BlobKey(bucket, "a.pdf"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "/raw/a.pdf", "raw//a.pdf", "raw/../a.pdf", "./a.pdf", "a/."})
  void new_invalidKey_isRejected(String key) {
    assertThatIllegalArgumentException().isThrownBy(() -> new BlobKey("raw", key));
  }

  @Test
  void new_nulls_areRejected() {
    assertThatNullPointerException().isThrownBy(() -> new BlobKey(null, "a.pdf"));
    assertThatNullPointerException().isThrownBy(() -> new BlobKey("raw", null));
  }
}
