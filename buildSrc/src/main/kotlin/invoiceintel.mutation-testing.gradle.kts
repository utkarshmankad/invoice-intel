// PIT mutation testing for modules where a weak test is most costly (pii, validate).
// Runs nightly (docs/ci.md §2.1); `./gradlew :<module>:pitest` runs it locally.
plugins {
    id("invoiceintel.java-conventions")
    id("info.solidsoft.pitest")
}

val libs = versionCatalogs.named("libs")

pitest {
    pitestVersion = libs.findVersion("pitest").get().requiredVersion
    junit5PluginVersion = libs.findVersion("pitest-junit5").get().requiredVersion
    targetClasses = listOf("dev.invoiceintel.${project.name}.*")
    threads = 4
    outputFormats = listOf("HTML", "XML")
    timestampedReports = false
    failWhenNoMutations = false
}
