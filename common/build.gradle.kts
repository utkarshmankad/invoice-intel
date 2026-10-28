plugins {
    id("invoiceintel.java-conventions")
    // Synthetic invoice generator shared by module tests (architecture.md §13, ADR-14).
    `java-test-fixtures`
}

dependencies {
    // MDC keys for structured logs (NFR-6); the binding comes from app (Logback).
    api(libs.slf4j.api)
    // Tests need a real MDC adapter; without a provider SLF4J falls back to a no-op MDC.
    testRuntimeOnly(libs.logback.classic)

    // The Boot BOM versions slf4j-api, which testFixtures inherits from main.
    testFixturesImplementation(platform(libs.spring.boot.bom))
    testFixturesImplementation(libs.pdfbox)
    testImplementation(libs.pdfbox)
}
