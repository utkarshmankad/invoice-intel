plugins {
    id("invoiceintel.java-conventions")
}

dependencies {
    api(project(":common"))
    // Extraction records carry Bean Validation constraints (EXT-1, architecture.md §6.5).
    api(libs.jakarta.validation.api)

    // Hibernate Validator runs the constraints; the JSONB round-trip uses Jackson 3 (§7).
    testImplementation(libs.spring.boot.starter.validation)
    testImplementation(libs.jackson.databind)
    testImplementation(testFixtures(project(":common")))
}
