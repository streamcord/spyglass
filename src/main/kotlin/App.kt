package io.streamcord.spyglass

import com.mongodb.client.MongoCollection
import com.mongodb.client.model.Projections
import com.mongodb.client.model.changestream.OperationType
import kotlinx.coroutines.coroutineScope
import org.bson.Document
import org.tinylog.configuration.Configuration
import java.nio.ByteBuffer
import java.util.*

private fun generateSecret() = UUID.randomUUID().toString()
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

    logger.info("Using client ID [${clientID.value.ansiBold}] and callback URI https://${workerInfo.callback.ansiBold}")

    val database = DatabaseController.create(config.mongo)

    val (twitchClient, expiresIn) = TwitchClient.create(clientID, clientSecret, workerInfo.callback)
    logger.debug("Created Twitch client with new access token. Expires in $expiresIn seconds")

    TwitchServer.create(database, config.amqp).start()
    twitchClient.awaitCallbackAccess(TwitchServer.rootHttpStatus)

    // synchronize subscriptions between DB and twitch
    syncSubscriptions(twitchClient, database, removeFromDB = true, workerInfo)

    val eventHandler = EventHandler(4, workerInfo)
    eventHandler.collectIn(this)

    // find subscriptions without an associated notification and remove them
    database.subscriptions.find()
        .filter(workerInfo.filterBy("user_id"))
        .projection(Projections.include("user_id", "sub_id"))
        .forEach {
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
        if (workerInfo shouldNotHandle streamerID) return@forEach

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
            OperationType.INSERT, OperationType.UPDATE, OperationType.REPLACE ->
                doc.fullDocument!!.getString("streamer_id")?.toLong() ?: return@launchWatch
            OperationType.DELETE -> ByteBuffer.wrap(doc.documentKey!!.getBinary("_id").data).long
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
    database: DatabaseController,
    removeFromDB: Boolean,
    workerInfo: WorkerInfo
) {
    val twitchSubscriptions = twitchClient.fetchExistingSubscriptions()
        .filter { workerInfo shouldHandle it.condition.broadcaster_user_id }
        .map { it.id }

    logger.info("Fetched existing subscriptions from Twitch. Count: ${twitchSubscriptions.size}")

    val dbSubscriptions = database.subscriptions.find()
        .filter(workerInfo.filterBy("user_id"))
        .projection(Projections.include("sub_id"))
        .map { it.getString("sub_id") }
        .toList()

    logger.info("Found existing subscriptions in DB. Count: ${dbSubscriptions.size}")

    val missingFromTwitch = dbSubscriptions
        .filter { it !in twitchSubscriptions }
        .also { logger.info("Found ${it.size.ansiBold} subscriptions in DB that were not reported by Twitch") }

    val missingFromDB = twitchSubscriptions
        .filter { it !in dbSubscriptions }
        .also { logger.info("Received ${it.size.ansiBold} subscriptions from Twitch that were not in the DB") }

    if (removeFromDB) {
        missingFromTwitch
            .onEach { database.subscriptions.findOneAndDelete(Document("sub_id", it)) }
            .also { logger.info("Removed ${it.size.ansiBold} subscriptions from the DB that were not reported by Twitch") }
    }

    missingFromDB
        .filter { twitchClient.removeSubscription(it) }
        .also { logger.info("Removed ${it.size.ansiBold} subscriptions from Twitch that were not in the database") }
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
        twitchClient.createSubscription(streamerID, type, secret)?.forEach {
            subscriptions.insertSubscription(twitchClient.clientID, secret, it)
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
