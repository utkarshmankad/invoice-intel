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
    // OCR words with boxes and confidences (TXT-1, TXT-2, ADR-10); uses the system Tesseract.
    // pdfbox-tools only serves Tess4J's PDF-to-image helpers, which we don't use (PageRenderer
    // renders pages); it drags in pdfbox-debugger and Bouncy Castle 1.84 (GHSA-9pwp-9qqc-pr26).
    implementation(libs.tess4j) {
        exclude(group = "org.apache.pdfbox", module = "pdfbox-tools")
    }

    testImplementation(testFixtures(project(":common")))
}

// JNA doesn't search Homebrew's library folder; CI (Linux) finds libtesseract on the default path.
val homebrewLib = file("/opt/homebrew/lib")
if (homebrewLib.isDirectory) {
    tasks.withType<Test>().configureEach {
        systemProperty("jna.library.path", homebrewLib.absolutePath)
    }
}
