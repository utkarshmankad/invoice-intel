package dev.invoiceintel.storage;

import java.util.Objects;
import java.util.Optional;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/** {@link BlobStore} on any S3-compatible service: SeaweedFS locally, R2 in Phase 2 (NFR-11). */
public final class S3BlobStore implements BlobStore {

  private final S3Client s3;

  public S3BlobStore(S3Client s3) {
    this.s3 = Objects.requireNonNull(s3, "s3");
  }

  @Override
  public void put(BlobKey key, byte[] content, String contentType) {
    Objects.requireNonNull(content, "content");
    Objects.requireNonNull(contentType, "contentType");
    try {
      s3.putObject(
          b -> b.bucket(key.bucket()).key(key.key()).contentType(contentType),
          RequestBody.fromBytes(content));
    } catch (SdkException e) {
      throw failure("put", key, e);
    }
  }

  @Override
  public Optional<byte[]> get(BlobKey key) {
    try {
      return Optional.of(
          s3.getObjectAsBytes(b -> b.bucket(key.bucket()).key(key.key())).asByteArray());
    } catch (NoSuchKeyException e) {
      return Optional.empty();
    } catch (SdkException e) {
      throw failure("get", key, e);
    }
  }

  @Override
  public void delete(BlobKey key) {
    try {
      s3.deleteObject(b -> b.bucket(key.bucket()).key(key.key()));
    } catch (SdkException e) {
      throw failure("delete", key, e);
    }
  }

  // Operation and bucket only: enough to diagnose, and nothing that could carry PII (PII-8).
  private static BlobStoreException failure(String operation, BlobKey key, SdkException cause) {
    return new BlobStoreException("S3 " + operation + " failed in bucket " + key.bucket(), cause);
  }
}
