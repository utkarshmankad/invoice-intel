plugins {
    id("invoiceintel.spring-boot-app")
}

dependencies {
    implementation(project(":common"))
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.flyway)
    runtimeOnly(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)
    testImplementation(libs.spring.boot.starter.test)

    // The ArchUnit module rules (architecture.md §4.1) import the main classes of every
    // module. Test-only, so app's production classpath keeps only its allowed dependencies.
    testImplementation(libs.archunit.junit5)
    listOf("storage", "ingest", "text", "pii", "extract", "validate", "reconcile", "pipeline", "eval")
        .forEach { testImplementation(project(":$it")) }

    // Postgres via Testcontainers for the `test` profile (architecture.md §10).
    "integrationTestImplementation"(libs.spring.boot.testcontainers)
    "integrationTestImplementation"(libs.testcontainers.postgresql)
}

// `local` reads .env from the repository root (application-local.yaml).
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    workingDir = rootDir
}
