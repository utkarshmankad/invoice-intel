plugins {
    id("invoiceintel.mutation-testing")
}

dependencies {
    api(project(":common"))
    // ValidationResultStore writes the validation_result rows (architecture.md §7); app and
    // pipeline supply the JdbcClient.
    api(libs.spring.jdbc)
}
