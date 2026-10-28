plugins {
    id("invoiceintel.mutation-testing")
}

dependencies {
    api(project(":common"))
    // Detectors read the Text stage's TextLayer (architecture.md §4.1, §6.3).
    api(project(":text"))
    // HMAC tokens and the AEAD vault (PII-3, PII-7, architecture.md §6.3, ADR-06).
    implementation(libs.tink)

    testImplementation(testFixtures(project(":common")))
}
