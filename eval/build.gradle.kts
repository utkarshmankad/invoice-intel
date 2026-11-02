plugins {
    id("invoiceintel.java-conventions")
}

dependencies {
    api(project(":common"))
    // Label files are JSON (architecture.md §9.2).
    implementation(libs.jackson.databind)
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
