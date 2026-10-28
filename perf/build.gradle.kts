// Gatling simulations (docs/ci.md §5). No project dependencies: talks to a running app over
// HTTP only (architecture.md §4). CI runs:
//   ./gradlew :perf:gatlingRun -Dperf.baseUrl=http://127.0.0.1:8080 -Dperf.profile=ci
// with APP_ADMIN_PASSWORD in the environment. Assertions in the simulations fail the task.
plugins {
    id("invoiceintel.java-conventions")
    id("io.gatling.gradle")
}

// Gradle's own -D flags don't reach the forked Gatling JVM; pass the perf.* ones through.
tasks.withType<io.gatling.gradle.GatlingRunTask>().configureEach {
    systemProperties = providers.systemPropertiesPrefixedBy("perf.").get()
}
