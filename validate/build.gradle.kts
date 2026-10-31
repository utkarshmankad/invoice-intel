plugins {
    id("invoiceintel.mutation-testing")
}

dependencies {
    api(project(":common"))
    // ValidationResultStore writes the validation_result rows (architecture.md §7); app and
    // pipeline supply the JdbcClient.
    api(libs.spring.jdbc)
    // DecisionStore writes decision.reasons as JSONB (VAL-8, architecture.md §7).
    implementation(libs.jackson.databind)
}
