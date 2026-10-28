package dev.invoiceintel.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/** {@link S3BlobStore} against a real SeaweedFS S3 gateway (same image as compose.yaml). */
class S3BlobStoreIntegrationTest {

  private static final int S3_PORT = 8333;
  private static final String BUCKET = "raw";

  // Without an -s3.config identity file SeaweedFS accepts any credentials.
  @SuppressWarnings("resource")
  private static final GenericContainer<?> SEAWEEDFS =
      new GenericContainer<>(DockerImageName.parse("chrislusf/seaweedfs:4.51"))
          .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("weed"))
          .withCommand(
              "server", "-dir=/data", "-ip.bind=0.0.0.0", "-master.volumeSizeLimitMB=64", "-s3")
          .withExposedPorts(S3_PORT)
          .waitingFor(
              Wait.forHttp("/healthz").forPort(S3_PORT).withStartupTimeout(Duration.ofMinutes(2)));

  private static S3Client s3;
  private static BlobStore store;

  @BeforeAll
  static void start() {
    SEAWEEDFS.start();
    s3 =
        S3Client.builder()
            .endpointOverride(
                URI.create(
                    "http://" + SEAWEEDFS.getHost() + ":" + SEAWEEDFS.getMappedPort(S3_PORT)))
            .region(Region.US_EAST_1)
            .forcePathStyle(true)
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
            .build();
    s3.createBucket(b -> b.bucket(BUCKET));
    store = new S3BlobStore(s3);
  }

  @AfterAll
  static void stop() {
    s3.close();
    SEAWEEDFS.stop();
  }

  @Test
  void put_thenGet_returnsSameBytesAndContentType() {
    BlobKey key = new BlobKey(BUCKET, "raw/doc-1/original.pdf");
    byte[] content = "%PDF-1.7 synthetic".getBytes(StandardCharsets.US_ASCII);

    store.put(key, content, "application/pdf");

    assertThat(store.get(key)).hasValueSatisfying(bytes -> assertThat(bytes).isEqualTo(content));
    assertThat(s3.headObject(b -> b.bucket(BUCKET).key(key.key())).contentType())
        .isEqualTo("application/pdf");
  }

  @Test
  void put_existingKey_replacesContent() {
    BlobKey key = new BlobKey(BUCKET, "raw/doc-2/pages/1.png");
    store.put(key, new byte[] {1, 2, 3}, "image/png");

    store.put(key, new byte[] {4, 5}, "image/png");

    assertThat(store.get(key)).hasValueSatisfying(bytes -> assertThat(bytes).containsExactly(4, 5));
  }

  @Test
  void get_missingKey_isEmpty() {
    assertThat(store.get(new BlobKey(BUCKET, "raw/missing/original.pdf"))).isEmpty();
  }

  @Test
  void delete_existingKey_removesObject() {
    BlobKey key = new BlobKey(BUCKET, "raw/doc-3/original.pdf");
    store.put(key, new byte[] {9}, "application/pdf");

    store.delete(key);

    assertThat(store.get(key)).isEmpty();
  }

  @Test
  void delete_missingKey_doesNotFail() {
    BlobKey key = new BlobKey(BUCKET, "raw/never-written/original.pdf");

    store.delete(key);

    assertThat(store.get(key)).isEmpty();
  }

  @Test
  void putAndGet_missingBucket_throwBlobStoreException() {
    BlobKey key = new BlobKey("no-such-bucket", "raw/doc-4/original.pdf");

    assertThatExceptionOfType(BlobStoreException.class)
        .isThrownBy(() -> store.put(key, new byte[] {1}, "application/pdf"))
        .withMessage("S3 put failed in bucket no-such-bucket");
    assertThatExceptionOfType(BlobStoreException.class)
        .isThrownBy(() -> store.get(key))
        .withMessage("S3 get failed in bucket no-such-bucket");
  }
}
