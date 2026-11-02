// Gatling simulations (docs/ci.md §5). No project dependencies: talks to a running app over
// HTTP only (architecture.md §4). CI runs:
//   ./gradlew :perf:gatlingRun -Dperf.baseUrl=http://127.0.0.1:8080 -Dperf.profile=ci
// with APP_ADMIN_PASSWORD in the environment. Assertions in the simulations fail the task.
plugins {
    id("invoiceintel.java-conventions")
    id("io.gatling.gradle")
}

// The synthetic smoke invoice (docs/ci.md §4) is the upload in the pipeline simulations; read it
// from the app's fixtures instead of keeping a second copy.
sourceSets {
    named("gatling") {
        resources.srcDir(rootProject.file("app/src/integrationTest/resources/fixtures/smoke"))
    }
}

// Gradle's own -D flags don't reach the forked Gatling JVM; pass the perf.* ones through.
// Run every simulation (docs/ci.md §5) without the interactive picker, so the CI command needs
// no simulation names; a failed assertion in any of them fails the task.
tasks.withType<io.gatling.gradle.GatlingRunTask>().configureEach {
    runAllSimulations = true
    nonInteractive = true
    systemProperties = providers.systemPropertiesPrefixedBy("perf.").get()
}
