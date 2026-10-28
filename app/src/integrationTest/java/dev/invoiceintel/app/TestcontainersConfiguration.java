package dev.invoiceintel.app;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Postgres and SeaweedFS (S3) for the `test` profile (architecture.md §10); same images as
 * compose.yaml.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

  public static final String RAW_BUCKET = "raw";
  public static final String REDACTED_BUCKET = "redacted";

  private static final int S3_PORT = 8333;
  // Without an -s3.config identity file SeaweedFS accepts any credentials.
  private static final String ACCESS_KEY = "test";
  private static final String SECRET_KEY = "test";

  @Bean
  @ServiceConnection
  PostgreSQLContainer postgres() {
    return new PostgreSQLContainer("postgres:16.15-alpine");
  }

  @Bean
  GenericContainer<?> seaweedfs() {
    return new GenericContainer<>(DockerImageName.parse("chrislusf/seaweedfs:4.51"))
        .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("weed"))
        .withCommand(
            "server", "-dir=/data", "-ip.bind=0.0.0.0", "-master.volumeSizeLimitMB=64", "-s3")
        .withExposedPorts(S3_PORT)
        .waitingFor(
            Wait.forHttp("/healthz").forPort(S3_PORT).withStartupTimeout(Duration.ofMinutes(2)));
  }

  /** Points the app at the container and creates the buckets that compose's init job creates. */
  @Bean
  DynamicPropertyRegistrar storageProperties(GenericContainer<?> seaweedfs) {
    URI endpoint =
        URI.create("http://" + seaweedfs.getHost() + ":" + seaweedfs.getMappedPort(S3_PORT));
    try (var s3 =
        S3Client.builder()
            .endpointOverride(endpoint)
            .region(Region.US_EAST_1)
            .forcePathStyle(true)
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
            .build()) {
      s3.createBucket(b -> b.bucket(RAW_BUCKET));
      s3.createBucket(b -> b.bucket(REDACTED_BUCKET));
    }
    return registry -> {
      registry.add("invoice-intel.storage.endpoint", endpoint::toString);
      registry.add("invoice-intel.storage.bucket-raw", () -> RAW_BUCKET);
      registry.add("invoice-intel.storage.bucket-redacted", () -> REDACTED_BUCKET);
      registry.add("invoice-intel.storage.access-key", () -> ACCESS_KEY);
      registry.add("invoice-intel.storage.secret-key", () -> SECRET_KEY);
    };
  }
}
