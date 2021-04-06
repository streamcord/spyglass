package io.streamcord.spyglass

import com.mongodb.client.MongoCollection
import com.mongodb.client.model.changestream.OperationType
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import org.bson.Document
import org.bson.internal.Base64
import org.tinylog.configuration.Configuration
import java.nio.ByteBuffer
import kotlin.random.Random
import kotlin.system.exitProcess

private fun generateSecret() = Base64.encode(Random.nextBytes(32))
internal lateinit var logger: SpyglassLogger
    private set

suspend fun main() = coroutineScope {
    val workerIndex = System.getenv("SPYGLASS_WORKER_INDEX")?.toLongOrNull()
        ?: noEnv("No valid worker index found. Populate env variable SPYGLASS_WORKER_INDEX with the worker index.")

    val workerTotal = System.getenv("SPYGLASS_WORKER_TOTAL")?.toLongOrNull()
        ?: noEnv("No valid worker total found. Populate env variable SPYGLASS_WORKER_TOTAL with the total number of workers.")

    val config = loadConfig()

    // set default logging level and format based on config
    Configuration.set("writer.level", config.logging.level)
    Configuration.set("writer.format", config.logging.format)
    Configuration.set("exception", "keep: io.streamcord")

    logger = SpyglassLogger(workerIndex, config.logging)
    logger.info("Config read. Starting up worker")

    // global error handler just in case
    Thread.setDefaultUncaughtExceptionHandler { t, e ->
        logger.fatal(ExitCodes.UNCAUGHT_ERROR, "Fatal uncaught error in thread $t", e)
    }

    val clientID = ClientID(config.twitch.client_id)
    val clientSecret = ClientSecret(config.twitch.client_secret)
    val callbackUri = "$workerIndex.${config.twitch.base_callback}"

    logger.info("Starting worker $workerIndex of $workerTotal with client ID [${clientID.value.ansiBold}] and callback URI https://${callbackUri.ansiBold}")

    val database = DatabaseController.create(config.mongo)

    val (twitchClient, expiresIn) = TwitchClient.create(clientID, clientSecret, callbackUri)
    logger.debug("Created Twitch client with new access token. Expires in $expiresIn seconds")

    TwitchServer.create(database.subscriptions, config.amqp).start()

    // synchronize subscriptions between DB and twitch
    syncSubscriptions(twitchClient, database.subscriptions) { it % workerTotal == workerIndex }

    // find subscriptions without an associated notification and remove them
    database.subscriptions.find().forEach {
        val doc = Document("streamer_id", it.getLong("user_id").toString())

        if (database.notifications.countDocuments(doc) == 0L) {
            val subID = it.getString("sub_id")
            logger.info("Subscription with ID $subID has no associated notifications, attempting removal")
            if (twitchClient.removeSubscription(subID)) {
                database.subscriptions.deleteOne(it)
            }
        }
    }

    val eventHandler = EventHandler(4, workerIndex, workerTotal)

    database.notifications.find().forEach { doc ->
        val streamerID = doc.getString("streamer_id")?.toLong() ?: return@forEach
        eventHandler.submitEvent(streamerID) {
            maybeCreateSubscriptions(streamerID, doc, twitchClient, database.subscriptions)
        }
    }

    database.notifications.watch("insert", "delete", "update", "replace", "invalidate") { doc ->
        if (!doc.documentKey!!.isBinary("_id")) {
            logger.warn("Received change stream event for document without a binary _id field. Ignoring")
            return@watch
        }

        val streamerID = when (doc.operationType) {
            OperationType.INSERT -> doc.fullDocument!!.getString("streamer_id")?.toLong() ?: return@watch
            OperationType.DELETE, OperationType.UPDATE, OperationType.REPLACE ->
                ByteBuffer.wrap(doc.documentKey!!.getBinary("_id").data).long
            else -> logger.fatal(ExitCodes.WORST_CASE_SCENARIO, "DB INVALIDATION DETECTED. Halting and catching fire")
        }

        if (streamerID % workerTotal != workerIndex) return@watch

        eventHandler.submitEvent(streamerID) {
            when (doc.operationType) {
                OperationType.INSERT -> {
                    maybeCreateSubscriptions(streamerID, doc.fullDocument!!, twitchClient, database.subscriptions)
                }
                OperationType.DELETE -> {
                    maybeRemoveSubscriptions(streamerID, twitchClient, database)
                }
                OperationType.UPDATE -> { // on update or replace, treat as delete then reinsert
                    maybeRemoveSubscriptions(streamerID, twitchClient, database)
                    val docID = doc.documentKey!!.getBinary("_id")
                    val updatedDoc = database.notifications.find(Document("_id", docID)).first()!!
                    maybeCreateSubscriptions(streamerID, updatedDoc, twitchClient, database.subscriptions)
                }
                OperationType.REPLACE -> { // on replace, do the same thing as update, but don't look up the document
                    maybeRemoveSubscriptions(streamerID, twitchClient, database)
                    maybeCreateSubscriptions(streamerID, doc.fullDocument!!, twitchClient, database.subscriptions)
                }
                else -> logger.debug("Obtained change stream event with type ${doc.operationType.value}, ignoring")
            }
        }
    }.launchIn(this)

    eventHandler.collectIn(this)
}

private val Any.ansiBold get() = "\u001B[1m$this\u001B[0m"

private fun noEnv(message: String): Nothing {
    System.err.println("Fatal error: $message")
    exitProcess(ExitCodes.MISSING_ENV_VAR)
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
    val twitchSubscriptions = twitchClient.fetchExistingSubscriptions()
        .associateBy { it.id }
        .filter { filter(it.value.condition.broadcaster_user_id) }
    logger.info("Fetched existing subscriptions from Twitch. Count for this worker: ${twitchSubscriptions.size}")

    val dbSubscriptions = collection.find()
        .filter { filter(it.getLong("user_id")) }
        .associateBy { it.getString("sub_id") }
    logger.info("Found existing subscriptions in DB. Count for this worker: ${dbSubscriptions.size}")

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
        }
    }

    missingFromTwitch.forEach {
        collection.findOneAndDelete(Document("sub_id", it.key))
        replaceSubscription(it.value)
    }

    missingFromDB
        .filter { twitchClient.removeSubscription(it.key) }
        .forEach { replaceSubscription(it.value) }
}

suspend fun maybeCreateSubscriptions(
    streamerID: Long,
    document: Document,
    twitchClient: TwitchClient,
    subscriptions: MongoCollection<Document>
) {
    val onlineFilter = document {
        append("user_id", streamerID)
        append("type", "stream.online")
    }
    val offlineFilter = document {
        append("user_id", streamerID)
        append("type", "stream.offline")
    }

    tailrec suspend fun createSubscriptionTailrec(type: String, secret: String) {
        val result = twitchClient.createSubscription(streamerID, type, secret)
        if (result != null) {
            subscriptions.insertSubscription(twitchClient.clientID, secret, result)
            logger.info("Created subscription with type $type for user ID $streamerID")
        } else {
            delay(30000)
            createSubscriptionTailrec(type, secret)
        }
    }

    if (subscriptions.countDocuments(onlineFilter) == 0L)
        createSubscriptionTailrec("stream.online", generateSecret())

    val streamEndAction = document.getInteger("stream_end_action")
    if (streamEndAction != 0 && subscriptions.countDocuments(offlineFilter) == 0L) {
        createSubscriptionTailrec("stream.offline", generateSecret())
    }
}

suspend fun maybeRemoveSubscriptions(streamerID: Long, twitchClient: TwitchClient, database: DatabaseController) {
    val existingNotifications = database.notifications.find(Document("streamer_id", streamerID.toString()))

    tailrec suspend fun removeSubscription(document: Document, subID: String) {
        if (twitchClient.removeSubscription(subID)) {
            database.subscriptions.deleteOne(document)
            logger.info("Removed subscription with ID $subID")
        } else {
            // failed to remove subscription, try again in 30 seconds
            delay(30000)
            removeSubscription(document, subID)
        }
    }

    when {
        // if there are no notifications left for this streamer, delete all subscriptions for that user
        existingNotifications.none() -> database.subscriptions.find(Document("user_id", streamerID)).forEach {
            removeSubscription(it, it.getString("sub_id"))
        }
        // if there are notifications but none with stream_end_action, delete all offline subscriptions instead
        existingNotifications.none { it.getInteger("stream_end_action") != 0 } ->
            database.subscriptions.find(Document("user_id", streamerID).append("type", "stream.offline")).forEach {
                removeSubscription(it, it.getString("sub_id"))
            }
    }
}
