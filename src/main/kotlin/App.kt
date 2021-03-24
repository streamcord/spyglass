package io.streamcord.spyglass

import com.mongodb.client.MongoCollection
import com.mongodb.client.model.changestream.ChangeStreamDocument
import com.mongodb.client.model.changestream.OperationType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.bson.Document
import org.bson.internal.Base64
import java.nio.ByteBuffer
import kotlin.random.Random
import kotlin.system.exitProcess

private fun generateSecret() = Base64.encode(Random.nextBytes(32))
internal val logger = KotlinLogging.logger {}

suspend fun main() = coroutineScope<Unit> {
    val workerIndex = System.getenv("SPYGLASS_WORKER_INDEX")?.toLongOrNull()
        ?: noEnv("No valid worker index found. Populate env variable SPYGLASS_WORKER_INDEX with the worker index.")

    val workerTotal = System.getenv("SPYGLASS_WORKER_TOTAL")?.toLongOrNull()
        ?: noEnv("No valid worker total found. Populate env variable SPYGLASS_WORKER_TOTAL with the total number of workers.")

    val config = loadConfig()
    logger.info("Config read. Starting up worker")
    val clientID = ClientID(config.twitch.client_id)
    val clientSecret = ClientSecret(config.twitch.client_secret)
    val callbackUri = "$workerIndex.${config.twitch.base_callback}"

    logger.info("Starting worker $workerIndex of $workerTotal with client ID [${clientID.value.ansiBold}] and callback URI https://${callbackUri.ansiBold}")

    val database = DatabaseController.create(config.mongo)

    val (twitchClient, expiresIn) = TwitchClient.create(clientID, clientSecret, callbackUri)
    logger.debug("Created Twitch client with new access token. Expires in $expiresIn seconds")

    TwitchServer.create(database.subscriptions, config.aqmp).start()

    // synchronize subscriptions between DB and twitch
    syncSubscriptions(twitchClient, database.subscriptions) { it % workerTotal == workerIndex }

    // find subscriptions without an associated notification and remove them
    database.subscriptions.find().forEach {
        val doc = Document("streamer_id", it.getLong("user_id").toString())

        if (database.notifications.find(doc).none()) {
            val subID = it.getString("sub_id")
            logger.warn("Subscription with ID $subID has no associated notifications, attempting removal")
            if (twitchClient.removeSubscription(subID)) {
                database.subscriptions.deleteOne(it)
            }
        }
    }

    fun processNotification(doc: Document) {
        maybeCreateSubscriptions(doc, twitchClient, database.subscriptions) { it % workerTotal == workerIndex }
    }
    database.notifications.find().forEach { processNotification(it) }
    database.notifications.watch("insert", "delete") { doc ->
        if (doc.operationType == OperationType.INSERT) {
            processNotification(doc.fullDocument!!)
        } else if (doc.operationType == OperationType.DELETE) {
            maybeRemoveSubscriptions(doc, twitchClient, database) { it % workerTotal == workerIndex }
        }
    }.launchIn(this)
}

private val Any.ansiBold get() = "\u001B[1m$this\u001B[0m"

private fun noEnv(message: String): Nothing {
    logger.error(message)
    exitProcess(1)
}

/**
 * Synchronizes subscriptions between Twitch and the database. If any subscriptions are reported by Twitch that aren't
 * in the database, they are added to the database. If any subscriptions are in the database that weren't reported by
 * Twitch, they are recreated and replaced in the database.
 */
private suspend fun syncSubscriptions(
    twitchClient: TwitchClient,
    collection: MongoCollection<Document>,
    filter: (Long) -> Boolean
) {
    val twitchSubscriptions = twitchClient.fetchExistingSubscriptions().associateBy { it.id }
    logger.info("Fetched existing subscriptions from Twitch. Count: ${twitchSubscriptions.size}")

    val dbSubscriptions = collection.find()
        .filter { filter(it.getLong("user_id")) }
        .associateBy { it.getString("sub_id") }
    logger.info("Found existing subscriptions in DB. Count: ${dbSubscriptions.size}")

    class MissingSubscription(val userID: Long, val type: String)

    val missingFromTwitch = dbSubscriptions
        .filter { it.key !in twitchSubscriptions }
        .mapValues {
            val userID = it.value.getLong("user_id")
            val type = it.value.getString("type")
            MissingSubscription(userID, type)
        }
        .also { logger.info("Found ${it.size.ansiBold} subscriptions in DB that were not reported by Twitch") }

    val missingFromDB = twitchSubscriptions
        .filter { it.key !in dbSubscriptions }
        .mapValues { MissingSubscription(it.value.condition.broadcaster_user_id, it.value.type) }
        .also { logger.info("Received ${it.size.ansiBold} subscriptions from Twitch that were not in the DB") }

    suspend fun replaceSubscription(sub: MissingSubscription) {
        val secret = generateSecret()
        twitchClient.createSubscription(sub.userID, sub.type, secret)?.let {
            collection.insertSubscription(twitchClient.clientID, secret, it)
            logger.info("Recreated missing subscription for user with ID ${sub.userID} and type ${sub.type}")
        } ?: logger.warn("Failed to recreate missing subscription for user with ID ${sub.userID} and type ${sub.type}")
    }

    missingFromTwitch.forEach {
        collection.findOneAndDelete(Document("sub_id", it.key))
        replaceSubscription(it.value)
    }

    missingFromDB
        .filter { twitchClient.removeSubscription(it.key) }
        .forEach { replaceSubscription(it.value) }
}

private val subTypes = arrayOf("stream.online", "stream.offline")

fun CoroutineScope.maybeCreateSubscriptions(
    document: Document,
    twitchClient: TwitchClient,
    subscriptions: MongoCollection<Document>,
    filter: (Long) -> Boolean
) {
    val streamerID = document.getString("streamer_id").toLong()
    if (!filter(streamerID)) return

    val existing = subscriptions.find(Document("user_id", streamerID))

    launch {
        subTypes.filter { type -> existing.none { it.getString("type") == type } }.forEach { type ->
            val secret = generateSecret()
            twitchClient.createSubscription(streamerID, type, secret)?.let {
                subscriptions.insertSubscription(twitchClient.clientID, secret, it)
                logger.info("Created subscription with type $type for user ID $streamerID")
            }
        }
    }
}

suspend fun maybeRemoveSubscriptions(
    document: ChangeStreamDocument<Document>,
    twitchClient: TwitchClient,
    database: DatabaseController,
    filter: (Long) -> Boolean
) {
    val streamerID = ByteBuffer.wrap(document.documentKey!!.getBinary("_id").data).long
    if (!filter(streamerID)) return

    val otherNotifications = database.notifications.find(Document("streamer_id", streamerID.toString()))
    if (otherNotifications.any()) {
        // other notifications for this streamer still exist, so we don't need to delete the subscriptions
        logger.info("Other notifications still exist for streamer ID $streamerID, not removing subscriptions")
        return
    }

    database.subscriptions.find(Document("user_id", streamerID)).forEach {
        val subID = it.getString("sub_id")
        if (!twitchClient.removeSubscription(subID)) {
            // failed to remove subscription, just exit. we'll try again later
            logger.warn("Failed to remove subscription with ID $subID")
            return
        }
        database.subscriptions.deleteOne(it)
        logger.info("Removed subscription with ID $subID")
    }
}
