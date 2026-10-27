plugins {
    id("invoiceintel.java-conventions")
}

dependencies {
    // MDC keys for structured logs (NFR-6); the binding comes from app (Logback).
    api(libs.slf4j.api)
    // Tests need a real MDC adapter; without a provider SLF4J falls back to a no-op MDC.
    testRuntimeOnly(libs.logback.classic)
}
