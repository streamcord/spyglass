import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.4.31"
    kotlin("plugin.serialization") version "1.4.31"
    id("com.github.ben-manes.versions") version "0.38.0"
    id("com.github.johnrengelman.shadow") version "6.1.0"
    application
}

group = "io.streamcord"
version = "indev"

repositories {
    mavenCentral()
}

dependencies {
    // HTTP Server
    implementation("io.ktor", "ktor-server-netty", "1.5.2")
    implementation("org.slf4j", "slf4j-simple", "2.0.0-alpha1")

    // HTTP Client
    implementation("io.ktor", "ktor-client-java", "1.5.2")
    implementation("io.ktor", "ktor-client-logging", "1.5.2")

    // Serialization
    implementation("org.jetbrains.kotlinx", "kotlinx-serialization-json", "1.1.0")
    implementation("com.charleskorn.kaml", "kaml", "0.28.3")

    // DB
    implementation("org.mongodb", "mongodb-driver", "3.12.8")

    // MQ
    implementation("com.rabbitmq", "amqp-client", "5.11.0")

    // Logging
    implementation("io.github.microutils", "kotlin-logging", "2.0.6")

    // Tests
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter", "junit-jupiter", "5.7.0")
}

kotlin.sourceSets["main"].languageSettings.apply {
    useExperimentalAnnotation("kotlin.RequiresOptIn")
    enableLanguageFeature("InlineClasses")
}

tasks {
    withType<KotlinCompile>().configureEach {
        kotlinOptions.jvmTarget = "11" // require Java 11 so we can use the built-in HttpClient
        kotlinOptions.useIR = true
        kotlinOptions.languageVersion = "1.5" // for sealed interfaces and value classes
    }

    withType<Test>().configureEach {
        useJUnitPlatform() // required for JUnit 5
    }

    shadowJar {
        // minimize() // remove unused symbols from the fat JAR
    }
}

application {
    @Suppress("DEPRECATED") // this has to be used for the time being to avoid pissing off shadowJar
    mainClassName = "io.streamcord.webhooks.server.AppKt"
}
