plugins {
    `java-library`
}

description = "Boins core — minimal, fast blob storage engine (embedded mode)"

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
