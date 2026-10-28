plugins {
    id("invoiceintel.mutation-testing")
}

dependencies {
    api(project(":common"))
    // Detectors read the Text stage's TextLayer (architecture.md §4.1, §6.3).
    api(project(":text"))

    testImplementation(testFixtures(project(":common")))
}
