import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.6.0"
    kotlin("plugin.serialization") version "1.6.0"
    id("com.github.ben-manes.versions") version "0.39.0"
    id("com.github.johnrengelman.shadow") version "7.1.0"
    id("com.palantir.docker") version "0.30.0"
    application
}

group = "io.streamcord"
version = "1.1.0-M1"

repositories {
    mavenCentral()
}

dependencies {
    // HTTP Server and Client
    implementation("io.ktor", "ktor-server-cio", "1.6.5")
    implementation("io.ktor", "ktor-client-java", "1.6.5")

    // Serialization
    implementation("org.jetbrains.kotlinx", "kotlinx-serialization-json", "1.3.1")
    implementation("com.charleskorn.kaml", "kaml", "0.37.0")

    // DB
    implementation("org.mongodb", "mongodb-driver", "3.12.10")

    // MQ
    implementation("com.rabbitmq", "amqp-client", "5.14.0")

    // Logging
    implementation("org.tinylog", "slf4j-tinylog", "2.3.2")
    implementation("org.tinylog", "tinylog-impl", "2.3.2")

    // Tests
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter", "junit-jupiter", "5.8.1")
}

kotlin.sourceSets["main"].languageSettings.apply {
    optIn("kotlin.RequiresOptIn")
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
        val regex = "(RC\\d*|M\\d+|rc\\d*)".toRegex() // match milestones and release candidates
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
