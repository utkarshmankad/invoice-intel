plugins {
    id("invoiceintel.spring-boot-app")
}

dependencies {
    implementation(project(":common"))
    implementation(project(":storage"))
    implementation(project(":ingest"))
    implementation(project(":pipeline"))
    // Typed `invoice-intel.pii.*` config builds PiiSettings (architecture.md §4.1, §10).
    implementation(project(":pii"))
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.flyway)
    // Stage jobs on Postgres (ADR-04); JobRunr creates and owns its own tables.
    implementation(libs.jobrunr.spring.boot.starter)
    // @SuppressFBWarnings with a justification, for findings reviewed by the owner (ADR-12).
    compileOnly(libs.spotbugs.annotations)
    runtimeOnly(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)
    testImplementation(libs.spring.boot.starter.test)

    // The ArchUnit module rules (architecture.md §4.1) import the main classes of every
    // module. Test-only, so app's production classpath keeps only its allowed dependencies.
    testImplementation(libs.archunit.junit5)
    listOf("text", "pii", "extract", "validate", "reconcile", "eval")
        .forEach { testImplementation(project(":$it")) }

    // Postgres via Testcontainers for the `test` profile (architecture.md §10).
    "integrationTestImplementation"(libs.spring.boot.testcontainers)
    "integrationTestImplementation"(libs.testcontainers.postgresql)
    // SeaweedFS (S3) for upload tests, and the generator for the smoke fixture (M1.3).
    "integrationTestImplementation"(libs.testcontainers)
    "integrationTestImplementation"(testFixtures(project(":common")))
    // Waits for stage jobs in pipeline tests (testing.md: no sleeps).
    "integrationTestImplementation"(libs.awaitility)
    // Builds image-only (scanned) PDFs for the Text stage test.
    "integrationTestImplementation"(libs.pdfbox)
}

// `local` reads .env from the repository root (application-local.yaml).
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    workingDir = rootDir
}

// The Text stage calls the system Tesseract through JNA (architecture.md §6.2). JNA doesn't search
// Homebrew's folders and Homebrew keeps the language data elsewhere than the container image;
// CI (Linux) uses the defaults.
val homebrewLib = file("/opt/homebrew/lib")
val homebrewTessdata = file("/opt/homebrew/share/tessdata")
if (homebrewLib.isDirectory) {
    tasks.withType<Test>().configureEach {
        systemProperty("jna.library.path", homebrewLib.absolutePath)
        if (homebrewTessdata.isDirectory) {
            systemProperty("invoice-intel.text.tessdata-path", homebrewTessdata.absolutePath)
        }
    }
    tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
        systemProperty("jna.library.path", homebrewLib.absolutePath)
    }
}
