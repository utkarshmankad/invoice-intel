plugins {
    id("invoiceintel.java-conventions")
}

dependencies {
    api(project(":common"))
    // Stage jobs are JobRunr job requests (ADR-04); app adds the Spring Boot starter.
    api(libs.jobrunr)
    // PipelineService takes a JdbcClient and a transaction manager; app supplies both.
    api(libs.spring.jdbc)
    // TextStageHandler reads originals from object storage; app passes the BlobStore.
    api(project(":storage"))
    // The Text stage handler wires the text module (TXT-1..4, architecture.md §5.2).
    implementation(project(":text"))
    implementation(libs.slf4j.api)
}
