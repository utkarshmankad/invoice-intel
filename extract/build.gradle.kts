plugins {
    id("invoiceintel.java-conventions")
}

dependencies {
    api(project(":common"))
    // Extraction records carry Bean Validation constraints (EXT-1, architecture.md §6.5).
    api(libs.jakarta.validation.api)
    // `invoice-intel.llm.*` binds to LlmProperties (EXT-3, architecture.md §10).
    api(libs.spring.boot)

    // Hibernate Validator runs the constraints; the JSONB round-trip uses Jackson 3 (§7).
    testImplementation(libs.spring.boot.starter.validation)
    testImplementation(libs.jackson.databind)
    testImplementation(testFixtures(project(":common")))
}
