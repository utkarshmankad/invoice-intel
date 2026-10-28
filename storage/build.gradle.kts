plugins {
    id("invoiceintel.java-conventions")
}

dependencies {
    api(project(":common"))

    // S3-compatible object storage (NFR-11): SeaweedFS locally, R2 in Phase 2. `api` because
    // S3BlobStore is built from an S3Client.
    api(platform(libs.aws.sdk.bom))
    api(libs.aws.sdk.s3) {
        // Only the sync client is used; the async Netty transport isn't needed.
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }

    "integrationTestImplementation"(libs.testcontainers)
}
