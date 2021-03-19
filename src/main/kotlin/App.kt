package io.streamcord.spyglass

import com.mongodb.client.MongoClients
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.Aggregates.match
import com.mongodb.client.model.Filters.`in`
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.bson.Document
import kotlin.random.Random
import kotlin.system.exitProcess

internal val TEMP_SECRET = Random.nextBytes(16).decodeToString()
internal val logger = KotlinLogging.logger {}

suspend fun main() = coroutineScope<Unit> {
    val workerIndex = System.getenv("SPYGLASS_WORKER_INDEX")?.toIntOrNull()
        ?: noEnv("No valid worker index found. Populate env variable SPYGLASS_WORKER_INDEX with the worker index.")

    val workerTotal = System.getenv("SPYGLASS_WORKER_TOTAL")?.toIntOrNull()
        ?: noEnv("No valid worker total found. Populate env variable SPYGLASS_WORKER_TOTAL with the total number of workers.")

    val config = loadConfig()
    logger.info("Config read. Starting up worker")
    val clientID = ClientID(config.twitch.client_id)
    val clientSecret = ClientSecret(config.twitch.client_secret)
    val callbackUri = "$workerIndex.${config.twitch.base_callback}"

    logger.info("Starting worker $workerIndex of $workerTotal with client ID [${clientID.value.ansiBold}] and callback URI https://${callbackUri.ansiBold}")

    val mongoClient = MongoClients.create(config.mongo.connection)
    val database = mongoClient.getDatabase(config.mongo.database)

    val (twitchClient, expiresIn) = TwitchClient.create(clientID, clientSecret, callbackUri)
    logger.debug("Created Twitch client with new access token. Expires in $expiresIn seconds")

    val collection = database.getCollection(config.mongo.collections.subscriptions)
    TwitchServer.create(collection, config.aqmp).start()

    // synchronize subscriptions between DB and twitch
    logger.debug(
        "Found subscriptions in DB. Count: ${collection.countDocuments(Document("client_id", clientID.value))}"
    )
    syncSubscriptions(twitchClient, collection)

    val notificationsCollection = database.getCollection(config.mongo.collections.notifications)
    val notificationsDeletionQueue = database.getCollection(config.mongo.collections.notifications_deletion_queue)

    notificationsDeletionQueue.find(Document("client_id", clientID.value)).forEach {
        maybeRemoveSubscriptions(it, twitchClient, notificationsCollection, notificationsDeletionQueue, collection)
    }

    notificationsDeletionQueue.watch(listOf(match(`in`("operationType", listOf("insert")))))
        .asFlow()
        .onEach { streamDocument ->
            val fullDocument = streamDocument.fullDocument!!

            maybeRemoveSubscriptions(
                fullDocument,
                twitchClient, notificationsCollection, notificationsDeletionQueue, collection
            )
        }.catch { cause -> logger.warn("Exception in deletion queue change stream watch: $cause") }
        .launchIn(this)

    collection.find(Document("client_id", clientID.value)).forEach {
        val doc = document {
            append("client_id", clientID.value)
            append("streamer_id", it.getString("user_id"))
        }
        if (notificationsCollection.find(doc).none()) {
            val subID = it.getString("sub_id")
            logger.warn("Subscription with ID $subID has no associated notifications, attempting removal")
            if (twitchClient.removeSubscription(subID)) {
                collection.deleteOne(it)
            }
        }
    }

    notificationsCollection.find(Document("client_id", clientID.value)).forEach {
        maybeCreateSubscriptions(it, twitchClient, collection)
    }

    notificationsCollection.watch(listOf(match(`in`("operationType", listOf("insert")))))
        .asFlow()
        .onEach { streamDocument ->
            val fullDocument = streamDocument.fullDocument!!

            maybeCreateSubscriptions(fullDocument, twitchClient, collection)
        }
        .catch { cause -> logger.warn("Exception in notifications change stream watch: $cause") }
        .launchIn(this)
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
private suspend fun syncSubscriptions(twitchClient: TwitchClient, collection: MongoCollection<Document>) {
    val twitchSubscriptions = twitchClient.fetchExistingSubscriptions().associateBy { it.id }
    logger.info("Fetched existing subscriptions from Twitch. Count: ${twitchSubscriptions.size}")

    // create new subscriptions for any found in DB that weren't reported by Twitch
    collection.find(Document("client_id", twitchClient.clientID.value))
        .asSequence()
        .filter { val subID = it.getString("sub_id"); subID != null && subID !in twitchSubscriptions }
        .associateWith { twitchClient.createSubscription(it.getString("user_id"), it.getString("type")!!) }
        .filter {
            val isNull = it.value == null
            if (isNull) {
                logger.warn("Failed to recreate missing subscription with sub ID ${it.key.getString("sub_id")}, deleting")
                collection.deleteOne(it.key)
            }

            !isNull
        }
        .onEach { collection.updateSubscription(it.key, it.value!!.id) }
        .also { logger.info("Created ${it.size.ansiBold} subscriptions found in DB but not reported by Twitch") }

    // store subscriptions that were reported by Twitch but weren't in the DB
    val storedSubscriptions = collection.find(Document("client_id", twitchClient.clientID.value))
        .associateBy { it.getString("sub_id") }

    twitchSubscriptions.values
        .filter { it.id !in storedSubscriptions }
        .onEach { collection.insertSubscription(twitchClient.clientID.value, TEMP_SECRET, it) }
        .also { logger.info("Stored ${it.size.ansiBold} subscriptions reported by Twitch but not in DB") }
}

fun CoroutineScope.maybeCreateSubscriptions(
    document: Document,
    twitchClient: TwitchClient,
    subscriptionsCollection: MongoCollection<Document>
) {
    if (document.getString("client_id") != twitchClient.clientID.value) {
        logger.trace { "Received new notification request for different client ID" }
        return
    }
    val streamerID = document.getString("streamer_id")

    val existing = subscriptionsCollection.find(document {
        append("client_id", twitchClient.clientID.value)
        append("user_id", streamerID)
    })

    launch {
        listOf("stream.online", "stream.offline")
            .filter { type -> existing.none { it.getString("type") == type } }
            .forEach { type ->
                twitchClient.createSubscription(streamerID, type)?.let {
                    subscriptionsCollection.insertSubscription(twitchClient.clientID.value, TEMP_SECRET, it)
                }
                logger.info("Created subscription with type $type for user ID $streamerID")
            }
    }
}

suspend fun maybeRemoveSubscriptions(
    document: Document,
    twitchClient: TwitchClient,
    notificationsCollection: MongoCollection<Document>,
    notificationsDeletionQueue: MongoCollection<Document>,
    subscriptionsCollection: MongoCollection<Document>
) {
    if (document.getString("client_id") != twitchClient.clientID.value) {
        logger.trace { "Received new notification request for different client ID" }
        return
    }

    val streamerID = document.getString("streamer_id")
    val otherNotifications = notificationsCollection.find(Document("streamer_id", streamerID))
        .filterNot { it["_id"].toString() == document["_id"].toString() }

    if (otherNotifications.any()) {
        // other notifications for this streamer still exist, so we don't need to delete the subscriptions
        logger.info("Other notifications still exist for streamer ID $streamerID, not removing subscriptions")
        notificationsDeletionQueue.deleteOne(document)
        return
    }

    val existing = subscriptionsCollection.find(document {
        append("client_id", twitchClient.clientID.value)
        append("user_id", streamerID)
    })

    existing.forEach {
        val subID = it.getString("sub_id")
        if (!twitchClient.removeSubscription(subID)) {
            // failed to remove subscription, just exit. we'll try again later
            logger.warn("Failed to remove subscription with ID $subID")
            return
        }
        subscriptionsCollection.deleteOne(it)
        logger.info("Removed subscription with ID $subID")
    }

    notificationsCollection.deleteOne(document)
    notificationsDeletionQueue.deleteOne(document)
}
