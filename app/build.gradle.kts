plugins {
    id("invoiceintel.spring-boot-app")
}

dependencies {
    implementation(project(":common"))
    implementation(libs.spring.boot.starter)
    testImplementation(libs.spring.boot.starter.test)

    // The ArchUnit module rules (architecture.md §4.1) import the main classes of every
    // module. Test-only, so app's production classpath keeps only its allowed dependencies.
    testImplementation(libs.archunit.junit5)
    listOf("storage", "ingest", "text", "pii", "extract", "validate", "reconcile", "pipeline", "eval")
        .forEach { testImplementation(project(":$it")) }
}
