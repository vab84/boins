plugins {
    `java-library`
    application
}

description = "Boins server — S3-compatible HTTP interface with admin API"

application {
    mainClass.set("io.boins.server.Main")
}

dependencies {
    api(project(":boins-core"))

    implementation(libs.javalin)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.yaml)
    runtimeOnly(libs.slf4j.simple)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)

    // Real AWS SDK v2 as the compatibility reference for integration tests.
    testImplementation(libs.awssdk.s3)
    testImplementation(libs.awssdk.urlconnection)
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
        attributes["Implementation-Title"] = "boins-server"
        attributes["Implementation-Version"] = project.version
    }
}
