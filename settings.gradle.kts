rootProject.name = "invoice-intel"

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

// Modules and their allowed dependencies: architecture.md §4. `perf` is added in plan M0.15.
include(
    "common",
    "storage",
    "ingest",
    "text",
    "pii",
    "extract",
    "validate",
    "reconcile",
    "pipeline",
    "app",
    "eval",
)
