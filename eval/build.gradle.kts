plugins {
    id("invoiceintel.java-conventions")
    application
}

dependencies {
    api(project(":common"))
    // Label files are JSON (architecture.md §9.2).
    implementation(libs.jackson.databind)
    // The eval CLI (architecture.md §3, §6.9).
    implementation(libs.picocli)
    compileOnly(libs.spotbugs.annotations)
    // Tests compile against EvalCli, whose constructor carries @SuppressFBWarnings (-Werror).
    testCompileOnly(libs.spotbugs.annotations)
}

// ./gradlew :eval:run --args="run --dataset data/labels --strategy TEXT" (architecture.md §6.9)
application {
    mainClass = "dev.invoiceintel.eval.Main"
}

tasks.named<JavaExec>("run") {
    // Dataset and report paths are relative to the repository root.
    workingDir = rootDir
}

// Owner's check of hand-labelled files (plan D5): ./gradlew :eval:checkLabels --args="data/labels data/raw"
// Prints label file names, JSON paths and counts only (.claude/rules/pii.md).
tasks.register<JavaExec>("checkLabels") {
    group = "verification"
    description = "Checks label files against the label format (architecture.md §9.2)."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "dev.invoiceintel.eval.label.LabelCheck"
    workingDir = rootDir
}
