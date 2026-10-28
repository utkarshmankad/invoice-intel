plugins {
    id("invoiceintel.java-conventions")
}

dependencies {
    api(project(":common"))
    // Stage jobs are JobRunr job requests (ADR-04); app adds the Spring Boot starter.
    api(libs.jobrunr)
    // PipelineService takes a JdbcClient and a transaction manager; app supplies both.
    api(libs.spring.jdbc)
    implementation(libs.slf4j.api)
}
