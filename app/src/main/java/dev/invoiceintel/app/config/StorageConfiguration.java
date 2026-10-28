package dev.invoiceintel.app.config;

import dev.invoiceintel.ingest.IngestService;
import dev.invoiceintel.storage.BlobStore;
import dev.invoiceintel.storage.S3BlobStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/** Object storage and ingest wiring from {@code invoice-intel.storage.*} (architecture.md §9.1). */
@Configuration(proxyBeanMethods = false)
public class StorageConfiguration {

  /**
   * Path-style addressing because SeaweedFS has no per-bucket host names. SeaweedFS ignores the
   * region and R2 accepts {@code us-east-1} as an alias of {@code auto}.
   */
  @Bean(destroyMethod = "close")
  S3Client s3Client(InvoiceIntelProperties properties) {
    var storage = properties.storage();
    return S3Client.builder()
        .endpointOverride(storage.endpoint())
        .region(Region.US_EAST_1)
        .forcePathStyle(true)
        .credentialsProvider(
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(storage.accessKey(), storage.secretKey())))
        .build();
  }

  @Bean
  BlobStore blobStore(S3Client s3) {
    return new S3BlobStore(s3);
  }

  @Bean
  IngestService ingestService(
      BlobStore blobStore, JdbcClient jdbc, InvoiceIntelProperties properties) {
    return new IngestService(blobStore, jdbc, properties.storage().bucketRaw());
  }
}
