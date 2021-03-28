package io.streamcord.spyglass

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import java.io.File
import kotlin.system.exitProcess

private const val configName = "spyglass.yml"

@Serializable
class AppConfig(val mongo: Mongo, val twitch: Twitch, val amqp: Amqp) {
    @Serializable
    class Mongo(val connection: String, val database: String, val collections: Collections) {
        @Serializable
        class Collections(val subscriptions: String, val notifications: String)
    }

    @Serializable
    class Twitch(val client_id: String, val client_secret: String, val base_callback: String)

    @Serializable
    class Amqp(val connection: String, val queue: String)
}

fun loadConfig(): AppConfig {
    val configFile = File(configName).takeIf { it.exists() } ?: run {
        System.err.println(
            """
                No $configName file in current directory. Create one and format it as such:
                
                mongo:
                    connection: <connection string for replica set, e.g. "mongodb://localhost">
                    database: <name of db, e.g. "eventsub">
                    collections:
                        subscriptions: <name of collection, e.g. "subscriptions">
                        workers: <name of collection, e.g. "workers">
                        notifications: <name of collection, e.g. "notifications">
                
                amqp:
                    connection: <connection string, e.g. "localhost">
                    exchange: <exchange name, e.g. "amq.direct">
                    queue: <queue name, e.g. "events">
            """.trimIndent()
        )
        exitProcess(1)
    }

    return Yaml.default.decodeFromString(configFile.readText())
}
