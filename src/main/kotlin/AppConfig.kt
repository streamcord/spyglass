package io.streamcord.spyglass

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import java.io.File
import kotlin.system.exitProcess

private const val configName = "spyglass.yml"

@Serializable
class AppConfig(val mongo: Mongo, val twitch: Twitch, val amqp: Amqp, val logging: Logging = Logging()) {
    @Serializable
    class Mongo(val connection: String, val database: String, val collections: Collections) {
        @Serializable
        class Collections(val subscriptions: String, val notifications: String)
    }

    @Serializable
    class Twitch(val client_id: String, val client_secret: String, val base_callback: String)

    @Serializable
    class Amqp(val connection: String, val queue: String, val authentication: Authentication?) {
        @Serializable
        class Authentication(val username: String, val password: String)
    }

    @Serializable
    class Logging(
        val level: String = "info",
        val format: String = "{date} [{thread}] {package} {level}: {message}",
        val error_webhook: String? = null
    )
}

fun loadConfig(): AppConfig {
    val configFile = File(configName).takeIf { it.exists() } ?: run {
        System.err.println(
            """
                No $configName file in current directory. Create one and format it as such:
                
                mongo:
                  connection: <connection string for replica set, e.g. "localhost?replicaSet=replicaset">
                  database: <name of db, e.g. "eventsub">
                  collections:
                    subscriptions: <name of collection, e.g. "subscriptions">
                    notifications: <name of collection, e.g. "notifications">
                
                twitch:
                  client_id: <Twitch client ID, e.g. "yuk4id1awfrr5qkj5yh8qzlgpg66">
                  client_secret: <Twitch client secret, e.g. "5j48e47jhzb55o7zainz7e7niist">
                  base_callback: <Webhook callback URL for EventSub notifications, e.g. "eventsub.streamcord.io">
                
                amqp:
                  connection: <connection string, e.g. "localhost">
                  queue: <queue name, e.g. "events">
            """.trimIndent()
        )
        exitProcess(ExitCodes.MISSING_CONFIG_FILE)
    }

    return Yaml.default.decodeFromString(configFile.readText())
}
