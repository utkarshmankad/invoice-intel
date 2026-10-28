plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
}

dependencies {
    implementation(libs.spring.boot.gradle.plugin)
    implementation(libs.spotless.gradle.plugin)
    implementation(libs.spotbugs.gradle.plugin)
    implementation(libs.pitest.gradle.plugin)
    implementation(libs.gatling.gradle.plugin)
    // The Gatling plugin brings Jackson 2.22.2, which has high-severity advisories.
    implementation(platform(libs.jackson2.bom))
}
