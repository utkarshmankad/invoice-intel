plugins {
    id("invoiceintel.java-conventions")
}

dependencies {
    api(project(":common"))
    // The gateway takes only RedactedContent (PII-1, architecture.md §4.1, §6.4); pii also brings
    // spring-jdbc for the llm_call rows (EXT-4, §7).
    api(project(":pii"))
    // Extraction records carry Bean Validation constraints (EXT-1, architecture.md §6.5).
    api(libs.jakarta.validation.api)
    // `invoice-intel.llm.*` binds to LlmProperties (EXT-3, architecture.md §10).
    api(libs.spring.boot)
    // LlmGateway talks to providers through Spring AI's ChatModel (architecture.md §3, §6.5).
    api(platform(libs.spring.ai.bom))
    api(libs.spring.ai.model)
    // Responses are parsed into the extraction records and validated (EXT-1).
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)
    // Chat clients built from config (ADR-18): Ollama for local models, Anthropic for the cloud.
    implementation(libs.spring.ai.ollama)
    implementation(libs.spring.ai.anthropic)

    testImplementation(libs.jackson.databind)
    testImplementation(testFixtures(project(":common")))
    testImplementation(testFixtures(project(":pii")))
    // Real provider clients against WireMock stubs; no real LLM calls (testing.md).
    testImplementation(libs.wiremock.standalone)
}
