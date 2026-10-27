// Shared setup for every Java module: toolchain, tests, coverage, formatting, static
// analysis and dependency locking. Task names here are the CI build contract (docs/ci.md §3).
import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort

plugins {
    `java-library`
    jacoco
    id("com.diffplug.spotless")
    id("com.github.spotbugs")
}

val libs = versionCatalogs.named("libs")
fun lib(alias: String) = libs.findLibrary(alias).get()
fun version(alias: String) = libs.findVersion(alias).get().requiredVersion

group = "dev.invoiceintel"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencyLocking {
    lockAllConfigurations()
}

// Writes this module's gradle.lockfile: ./gradlew resolveAndLockAll --write-locks
tasks.register("resolveAndLockAll") {
    description = "Resolves all configurations so --write-locks can record them."
    val writeLocks = gradle.startParameter.isWriteDependencyLocks
    val resolvable = configurations.filter { it.isCanBeResolved }
    doFirst { require(writeLocks) { "Run with --write-locks" } }
    doLast { resolvable.forEach { it.resolve() } }
}

// Integration tests live in src/integrationTest and run with `integrationTest`.
val integrationTest: SourceSet = sourceSets.create("integrationTest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    val bom = platform(lib("spring-boot-bom"))
    implementation(bom)
    testImplementation(bom)
    testImplementation(lib("junit-jupiter"))
    testImplementation(lib("assertj-core"))
    testRuntimeOnly(lib("junit-platform-launcher"))
    spotbugsPlugins(lib("findsecbugs"))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror", "-parameters"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // DJL and Tess4J load native libraries (architecture.md ADR-01).
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

val integrationTestTask = tasks.register<Test>("integrationTest") {
    description = "Runs integration tests (Testcontainers, WireMock)."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    shouldRunAfter(tasks.test)
}

tasks.check {
    dependsOn(integrationTestTask)
}

jacoco {
    toolVersion = version("jacoco")
}

spotless {
    java {
        target("src/*/java/**/*.java")
        googleJavaFormat(version("google-java-format"))
        removeUnusedImports()
        formatAnnotations()
    }
}

spotbugs {
    toolVersion = version("spotbugs")
    effort = Effort.MAX
    reportLevel = Confidence.LOW
}

// Only production code is a CI gate (`spotbugsMain`); test code isn't analysed.
tasks.named { it.startsWith("spotbugs") && it != "spotbugsMain" }.configureEach {
    enabled = false
}

tasks.withType<com.github.spotbugs.snom.SpotBugsTask>().configureEach {
    reports.create("html") { required = true }
}
