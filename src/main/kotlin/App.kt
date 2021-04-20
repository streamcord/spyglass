package io.streamcord.spyglass

import com.mongodb.client.MongoCollection
import com.mongodb.client.model.changestream.OperationType
import kotlinx.coroutines.coroutineScope
import org.bson.Document
import org.bson.internal.Base64
import org.tinylog.configuration.Configuration
import java.nio.ByteBuffer
import kotlin.random.Random

private fun generateSecret() = Base64.encode(Random.nextBytes(32))
internal lateinit var logger: SpyglassLogger
    private set

suspend fun main() = coroutineScope<Unit> {
    val workerInfo = fetchWorkerInfo()
    val config = loadConfig()

    // set default logging level and format based on config
    Configuration.set("writer.level", config.logging.level)
    Configuration.set("writer.format", config.logging.format)
    Configuration.set("exception", "keep: io.streamcord")

    logger = SpyglassLogger(workerInfo, config.logging)
    logger.info("Config read. Starting up worker ${workerInfo.index} of ${workerInfo.total}")

    // global error handler just in case
    Thread.setDefaultUncaughtExceptionHandler { t, e ->
        logger.fatal(ExitCodes.UNCAUGHT_ERROR, "Fatal uncaught error in thread $t", e)
    }

    val clientID = ClientID(config.twitch.client_id)
    val clientSecret = ClientSecret(config.twitch.client_secret)
    val callbackUri = "${workerInfo.index}.${config.twitch.base_callback}"

    logger.info("Using client ID [${clientID.value.ansiBold}] and callback URI https://${callbackUri.ansiBold}")

    val database = DatabaseController.create(config.mongo)

    val (twitchClient, expiresIn) = TwitchClient.create(clientID, clientSecret, callbackUri)
    logger.debug("Created Twitch client with new access token. Expires in $expiresIn seconds")

    TwitchServer.create(database, config.amqp).start()
    twitchClient.awaitCallbackAccess(TwitchServer.rootHttpStatus)

    // synchronize subscriptions between DB and twitch
    syncSubscriptions(twitchClient, database.subscriptions) { workerInfo shouldHandle it }

    val eventHandler = EventHandler(4, workerInfo)
    eventHandler.collectIn(this)

    // find subscriptions without an associated notification and remove them
    database.subscriptions.find().forEach {
        val userID = it.getLong("user_id")
        val doc = Document("streamer_id", userID.toString())

        if (database.notifications.countDocuments(doc) == 0L) {
            val subID = it.getString("sub_id")
            logger.info("Subscription with ID $subID has no associated notifications, attempting removal")
            eventHandler.submitEvent(userID) {
                if (twitchClient.removeSubscription(subID)) {
                    database.subscriptions.deleteOne(it)
                }
            }
        }
    }

    database.notifications.find().forEach { doc ->
        val streamerID = doc.getString("streamer_id")?.toLong() ?: return@forEach
        eventHandler.submitEvent(streamerID) {
            val streamEndAction = doc.getInteger("stream_end_action")
            maybeCreateSubscriptions(streamerID, streamEndAction, twitchClient, database.subscriptions)
        }
    }

    val opTypes = setOf(OperationType.INSERT, OperationType.REPLACE, OperationType.DELETE, OperationType.UPDATE)

    database.notifications.launchWatch(this, opTypes) { doc ->
        if (!doc.documentKey!!.isBinary("_id")) {
            logger.warn("Received change stream event for document without a binary _id field. Ignoring")
            return@launchWatch
        }

        val streamerID = when (doc.operationType) {
            OperationType.INSERT -> doc.fullDocument!!.getString("streamer_id")?.toLong() ?: return@launchWatch
            OperationType.DELETE, OperationType.UPDATE, OperationType.REPLACE ->
                ByteBuffer.wrap(doc.documentKey!!.getBinary("_id").data).long
            else -> return@launchWatch
        }

        if (workerInfo shouldNotHandle streamerID) return@launchWatch

        eventHandler.submitEvent(streamerID) {
            when (doc.operationType) {
                OperationType.INSERT -> {
                    val streamEndAction = doc.fullDocument!!.getInteger("stream_end_action")
                    maybeCreateSubscriptions(streamerID, streamEndAction, twitchClient, database.subscriptions)
                }
                OperationType.DELETE -> {
                    maybeRemoveSubscriptions(streamerID, twitchClient, database)
                }
                OperationType.UPDATE, OperationType.REPLACE -> { // on update or replace, treat as delete then reinsert
                    maybeRemoveSubscriptions(streamerID, twitchClient, database)
                    val streamEndAction = doc.fullDocument!!.getInteger("stream_end_action")
                    maybeCreateSubscriptions(streamerID, streamEndAction, twitchClient, database.subscriptions)
                }
                else -> logger.debug("Obtained change stream event with type ${doc.operationType.value}, ignoring")
            }
        }
    }

    // watch for revocation and handle accordingly
    val filter =
        Document.parse("{'updateDescription.updatedFields.revocation_reason': 'notification_failures_exceeded'}")

    database.subscriptions.launchWatch(this, setOf(OperationType.UPDATE), filter) {
        val userID = it.fullDocument!!.getLong("user_id")
        eventHandler.submitEvent(userID) {
            maybeRemoveSubscriptions(userID, twitchClient, database)
            val streamEndAction = it.fullDocument!!.getInteger("stream_end_action")
            maybeCreateSubscriptions(userID, streamEndAction, twitchClient, database.subscriptions)
        }
    }
}

private val Any.ansiBold get() = "\u001B[1m$this\u001B[0m"

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
    streamEndAction: Int,
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

    suspend fun createSubscription(type: String, secret: String) {
        twitchClient.createSubscription(streamerID, type, secret)?.let {
            subscriptions.insertSubscription(twitchClient.clientID, secret, it)
            logger.info("Created subscription with type $type for user ID $streamerID")
        }
    }

    if (subscriptions.countDocuments(onlineFilter) == 0L)
        createSubscription("stream.online", generateSecret())

    if (streamEndAction != 0 && subscriptions.countDocuments(offlineFilter) == 0L) {
        createSubscription("stream.offline", generateSecret())
    }
}

suspend fun maybeRemoveSubscriptions(streamerID: Long, twitchClient: TwitchClient, database: DatabaseController) {
    val existingNotifications = database.notifications.find(Document("streamer_id", streamerID.toString()))

    suspend fun removeSubscription(document: Document, subID: String) {
        if (twitchClient.removeSubscription(subID)) {
            database.subscriptions.deleteOne(document)
            logger.info("Removed subscription with ID $subID")
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
