import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.4.30-RC"
    kotlin("plugin.serialization") version "1.4.30-RC"
    id("com.github.ben-manes.versions") version "0.36.0"
    id("com.github.johnrengelman.shadow") version "6.1.0"
    application
}

group = "io.streamcord"
version = "0.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // HTTP Server
    implementation("io.ktor", "ktor-server-cio", "1.5.1")
    implementation("org.slf4j", "slf4j-simple", "2.0.0-alpha1")

    // HTTP Client
    implementation("io.ktor", "ktor-client-java", "1.5.1")

    // Serialization
    implementation("org.jetbrains.kotlinx", "kotlinx-serialization-json", "1.0.1")
    implementation("com.charleskorn.kaml", "kaml", "0.26.0")

    // DB
    implementation("org.mongodb", "mongodb-driver", "3.12.7")

    // MQ
    implementation("com.rabbitmq", "amqp-client", "5.10.0")

    // Tests
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter", "junit-jupiter", "5.7.0")
}

tasks {
    withType<KotlinCompile>().configureEach {
        kotlinOptions.jvmTarget = "11" // require Java 11 so we can use the built-in HttpClient
        kotlinOptions.useIR = true
    }

    withType<Test>().configureEach {
        useJUnitPlatform() // required for JUnit 5
    }

    shadowJar {
        minimize() // remove unused symbols from the fat JAR
    }
}

application {
    @Suppress("DEPRECATED") // this has to be used for the time being to avoid pissing off shadowJar
    mainClassName = "io.streamcord.webhooks.server.AppKt"
}
