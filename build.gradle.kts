import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.4.32"
    kotlin("plugin.serialization") version "1.4.32"
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
    implementation("io.ktor", "ktor-server-cio", "1.5.2")

    // HTTP Client
    implementation("io.ktor", "ktor-client-java", "1.5.2")

    // Serialization
    implementation("org.jetbrains.kotlinx", "kotlinx-serialization-json", "1.1.0")
    implementation("com.charleskorn.kaml", "kaml", "0.29.0")

    // DB
    implementation("org.mongodb", "mongodb-driver", "3.12.8")

    // MQ
    implementation("com.rabbitmq", "amqp-client", "5.11.0")

    // Logging
    implementation("org.tinylog", "slf4j-tinylog", "2.2.1")
    implementation("org.tinylog", "tinylog-impl", "2.2.1")

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
}

application {
    @Suppress("DEPRECATED") // this has to be used for the time being to avoid pissing off shadowJar
    mainClassName = "io.streamcord.spyglass.AppKt"
}
