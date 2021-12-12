package io.streamcord.spyglass

import com.charleskorn.kaml.Yaml
import com.mongodb.client.model.Filters
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
    class Twitch(val proxy: String, val client_id: String, val auth_token: String)

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
                  proxy: <Base proxy URL, e.g. "http://localhost:8181">
                  client_id: <Proxy client ID, e.g. "main">
                  auth_token: <Proxy auth token, e.g. "Password123">
                
                amqp:
                  connection: <connection string, e.g. "localhost">
                  queue: <queue name, e.g. "events">
            """.trimIndent()
        )
        exitProcess(ExitCodes.MISSING_CONFIG_FILE)
    }

    return Yaml.default.decodeFromString(configFile.readText())
}

data class WorkerInfo(val index: Long, val total: Long, val callback: String) {
    infix fun shouldHandle(userID: Long): Boolean = userID % total == index
    infix fun shouldNotHandle(userID: Long): Boolean = !shouldHandle(userID)
    fun filterBy(fieldName: String) = Filters.mod(fieldName, total, index)
}

fun fetchWorkerInfo(): WorkerInfo {
    fun noEnv(message: String): Nothing {
        System.err.println("Fatal error: $message")
        exitProcess(ExitCodes.MISSING_ENV_VAR)
    }

    val workerIndex = System.getenv("SPYGLASS_WORKER_INDEX")?.toLongOrNull()
        ?: noEnv("No valid worker index found. Populate env variable SPYGLASS_WORKER_INDEX with the worker index.")

    val workerTotal = System.getenv("SPYGLASS_WORKER_TOTAL")?.toLongOrNull()
        ?: noEnv("No valid worker total found. Populate env variable SPYGLASS_WORKER_TOTAL with the total number of workers.")

    val workerCallback = System.getenv("SPYGLASS_WORKER_CALLBACK")
        ?: noEnv("No valid worker callback found. Populate env variable SPYGLASS_WORKER_CALLBACK with the URI to use for Twitch callbacks.")

    return WorkerInfo(workerIndex, workerTotal, workerCallback)
}
