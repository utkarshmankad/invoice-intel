plugins {
    id("invoiceintel.spring-boot-app")
}

dependencies {
    implementation(project(":common"))
    implementation(libs.spring.boot.starter)
    testImplementation(libs.spring.boot.starter.test)
}
