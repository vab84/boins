plugins {
    java
}

allprojects {
    group = "io.boins"
    version = "2.0.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "java-library")

    repositories {
        mavenCentral()
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(24))
        }
        withSourcesJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // Benchmarks are opt-in: ./gradlew test -Dboins.bench=true
        systemProperty("boins.bench", System.getProperty("boins.bench", "false"))
        testLogging {
            events("failed", "skipped")
            showStackTraces = true
            showStandardStreams = System.getProperty("boins.bench") == "true"
        }
    }
}
