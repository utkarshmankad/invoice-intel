// Aggregated JaCoCo reports and the coverage gate (docs/ci.md §3). Report paths are
// build/reports/jacoco/<task>/<task>.csv; CI reads them, so don't rename these tasks.
plugins {
    base
    jacoco
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

val modules = subprojects.filter { it.name != "perf" }
val minCoverage = providers.gradleProperty("minCoverage").orElse("85")

fun execData(vararg testTasks: String) =
    files(modules.flatMap { m -> testTasks.map { m.layout.buildDirectory.file("jacoco/$it.exec") } })
        .filter { it.exists() }

fun JacocoReportBase.coverModules(vararg testTasks: String) {
    executionData.setFrom(execData(*testTasks))
    classDirectories.setFrom(modules.map { it.layout.buildDirectory.dir("classes/java/main") })
    sourceDirectories.setFrom(modules.map { it.layout.projectDirectory.dir("src/main/java") })
    dependsOn(modules.map { "${it.path}:classes" })
    mustRunAfter(testTasks.flatMap { t -> modules.map { "${it.path}:$t" } })
}

fun JacocoReport.csvAndHtml() {
    reports {
        csv.required = true
        html.required = true
        xml.required = false
    }
}

tasks.register<JacocoReport>("jacocoUnitReport") {
    description = "Aggregated unit-test coverage of all modules."
    coverModules("test")
    csvAndHtml()
}

tasks.register<JacocoReport>("jacocoIntegrationReport") {
    description = "Aggregated integration-test coverage of all modules."
    coverModules("integrationTest")
    csvAndHtml()
}

tasks.register<JacocoReport>("jacocoMergedReport") {
    description = "Merged unit + integration coverage of all modules."
    coverModules("test", "integrationTest")
    csvAndHtml()
}

val jacocoMergedCoverageVerification = tasks.register<JacocoCoverageVerification>("jacocoMergedCoverageVerification") {
    description = "Fails when merged line coverage is below -PminCoverage (default 85)."
    coverModules("test", "integrationTest")
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = minCoverage.map { it.toBigDecimal().movePointLeft(2) }.get()
            }
        }
    }
}

tasks.check {
    dependsOn(jacocoMergedCoverageVerification)
}

// Writes gradle.lockfile for the root project; each module registers the same task
// (invoiceintel.java-conventions). Run: ./gradlew resolveAndLockAll --write-locks
dependencyLocking {
    lockAllConfigurations()
}

tasks.register("resolveAndLockAll") {
    description = "Resolves all configurations so --write-locks can record them."
    val writeLocks = gradle.startParameter.isWriteDependencyLocks
    val resolvable = configurations.filter { it.isCanBeResolved }
    doFirst { require(writeLocks) { "Run with --write-locks" } }
    doLast { resolvable.forEach { it.resolve() } }
}
