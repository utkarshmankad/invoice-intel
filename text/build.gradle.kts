plugins {
    id("invoiceintel.java-conventions")
}

dependencies {
    api(project(":common"))
    // Text layer with word boxes from digital PDFs (TXT-1, TXT-2, architecture.md §6.2).
    implementation(libs.pdfbox)

    testImplementation(testFixtures(project(":common")))
}
