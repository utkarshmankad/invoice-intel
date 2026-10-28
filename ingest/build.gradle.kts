plugins {
    id("invoiceintel.java-conventions")
}

dependencies {
    api(project(":common"))
    api(project(":storage"))
    // IngestService takes a JdbcClient; app supplies the DataSource.
    api(libs.spring.jdbc)
    // Content-based type detection (ING-3); core only, no parsers (architecture.md §3).
    implementation(libs.tika.core)

    testImplementation(testFixtures(project(":common")))
}
