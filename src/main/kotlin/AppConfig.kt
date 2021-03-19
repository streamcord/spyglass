package io.streamcord.spyglass

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import java.io.File
import kotlin.system.exitProcess

private const val configName = "spyglass.yml"

@Serializable
class AppConfig(val mongo: Mongo, val twitch: Twitch, val aqmp: Aqmp) {
    @Serializable
    class Mongo(val connection: String, val database: String, val collections: Collections) {
        @Serializable
        class Collections(
            val subscriptions: String,
            val workers: String,
            val notifications: String,
            val notifications_deletion_queue: String
        )
    }

    @Serializable
    class Twitch(val client_id: String, val client_secret: String, val base_callback: String)

    @Serializable
    class Aqmp(val connection: String, val queue: String)
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
                        notifications_deletion_queue: <name of collection, e.g. "notifications_deletion_queue">
                
                aqmp:
                    connection: <connection string, e.g. "localhost">
                    exchange: <exchange name, e.g. "amq.direct">
                    queue: <queue name, e.g. "events">
            """.trimIndent()
        )
        exitProcess(1)
    }

    return Yaml.default.decodeFromString(configFile.readText())
}
