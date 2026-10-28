plugins {
    id("invoiceintel.java-conventions")
}

dependencies {
    api(project(":common"))
    // Page images go to the raw bucket with a blob_ref row (TXT-2, architecture.md §6.2, §9.1).
    api(project(":storage"))
    // PageImageStore takes a JdbcClient; app supplies the DataSource.
    api(libs.spring.jdbc)
    // Text layer with word boxes from digital PDFs (TXT-1, TXT-2, architecture.md §6.2).
    implementation(libs.pdfbox)
    // Deskew, binarize and denoise before OCR (TXT-3); bundles native libs per platform.
    implementation(libs.opencv)

    testImplementation(testFixtures(project(":common")))
}
