import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.5.10"
    kotlin("plugin.serialization") version "1.5.10"
    id("com.github.ben-manes.versions") version "0.39.0"
    id("com.github.johnrengelman.shadow") version "7.0.0"
    id("com.palantir.docker") version "0.26.0"
    application
}

group = "io.streamcord"
version = "1.0.2"

repositories {
    mavenCentral()
}

dependencies {
    // HTTP Server and Client
    implementation("io.ktor", "ktor-server-cio", "1.6.0")
    implementation("io.ktor", "ktor-client-java", "1.6.0")

    // Serialization
    implementation("org.jetbrains.kotlinx", "kotlinx-serialization-json", "1.2.1")
    implementation("com.charleskorn.kaml", "kaml", "0.34.0")

    // DB
    implementation("org.mongodb", "mongodb-driver", "3.12.8")

    // MQ
    implementation("com.rabbitmq", "amqp-client", "5.12.0")

    // Logging
    implementation("org.tinylog", "slf4j-tinylog", "2.3.1")
    implementation("org.tinylog", "tinylog-impl", "2.3.1")

    // Tests
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter", "junit-jupiter", "5.7.2")
}

kotlin.sourceSets["main"].languageSettings.apply {
    useExperimentalAnnotation("kotlin.RequiresOptIn")
}

tasks {
    withType<KotlinCompile>().configureEach {
        kotlinOptions.jvmTarget = "11" // require Java 11 so we can use the built-in HttpClient
        kotlinOptions.freeCompilerArgs += "-Xallow-result-return-type"
    }

    withType<Test>().configureEach {
        useJUnitPlatform() // required for JUnit 5
    }

    shadowJar {
        minimize {
            exclude(dependency("org.tinylog:.*:.*"))
        } // remove unused symbols from the fat JAR
    }

    dependencyUpdates {
        val regex = "(RC\\d*|M\\d+)".toRegex() // match milestones and release candidates
        rejectVersionIf { regex in candidate.version }
    }
}

application {
    mainClass.set("io.streamcord.spyglass.AppKt")
}

docker {
    setDockerfile(rootProject.projectDir.resolve("Dockerfile.host"))
    name = "streamcord/spyglass:$version"
    files(tasks.shadowJar.get().outputs)
}
