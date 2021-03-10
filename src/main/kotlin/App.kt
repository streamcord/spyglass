package io.streamcord.spyglass

import com.mongodb.client.MongoClients
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.Aggregates.match
import com.mongodb.client.model.Filters.`in`
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.bson.Document
import kotlin.random.Random
import kotlin.system.exitProcess

internal val TEMP_SECRET = Random.nextBytes(16).decodeToString()
internal val logger = KotlinLogging.logger {}

fun main() = runBlocking {
    val config = loadConfig()
    logger.info("Config read. Starting up worker")

    val clientID = System.getenv("SPYGLASS_CLIENT_ID")?.let { ClientID(it) }
        ?: noEnv("No client ID found. Populate env variable SPYGLASS_CLIENT_ID with the desired Twitch client ID.")

    val clientSecret = System.getenv("SPYGLASS_CLIENT_SECRET")?.let { ClientSecret(it) }
        ?: noEnv("No client secret found. Populate env variable SPYGLASS_CLIENT_SECRET with the desired Twitch client secret.")

    val callbackUri = System.getenv("SPYGLASS_WEBHOOK_CALLBACK")
        ?: noEnv("No webhook callback found. Populate env variable SPYGLASS_WEBHOOK_CALLBACK with the desired callback URI.")

    logger.info("Starting worker with client ID [${clientID.value.ansiBold}] and callback URI https://${callbackUri}")

    val mongoClient = MongoClients.create(config.mongo.connection)
    val database = mongoClient.getDatabase(config.mongo.database)

    // add worker to worker slot
    val workersCollection = database.getCollection(config.mongo.collections.workers)
    workersCollection.insertOne(Document("client_id", clientID.value).append("callback", callbackUri))

    // remove this worker from the collection on exit
    Runtime.getRuntime().addShutdownHook(Thread {
        workersCollection.deleteOne(Document("client_id", clientID.value))
    })

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
    watchNotificationCreation(twitchClient, notificationsCollection, collection)
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

private fun CoroutineScope.watchNotificationCreation(
    twitchClient: TwitchClient,
    notificationsCollection: MongoCollection<Document>,
    subscriptionsCollection: MongoCollection<Document>
) {
    notificationsCollection.watch(listOf(match(`in`("operationType", listOf("insert")))))
        .asFlow()
        .onEach { streamDocument ->
            val fullDocument = streamDocument.fullDocument!!

            if (fullDocument.getString("client_id") != twitchClient.clientID.value) {
                logger.trace { "Received new notification request for different client ID" }
                return@onEach
            }
            val streamerID = fullDocument.getString("streamer_id")

            val existing = subscriptionsCollection.find(document {
                append("client_id", twitchClient.clientID.value)
                append("user_id", streamerID)
            })

            // already have subscriptions for this user, return
            if (existing.any()) {
                logger.warn("Attempted to create a subscription for a user we already have a subscription for (ID $streamerID)")
                return@onEach
            }

            twitchClient.createSubscription(streamerID, "stream.online")?.let {
                subscriptionsCollection.insertSubscription(twitchClient.clientID.value, TEMP_SECRET, it)
            } ?: return@onEach

            twitchClient.createSubscription(streamerID, "stream.offline")?.let {
                subscriptionsCollection.insertSubscription(twitchClient.clientID.value, TEMP_SECRET, it)
            } ?: return@onEach

            logger.debug("Created subscription for ID $streamerID")
        }
        .catch { cause -> logger.warn("Exception in change stream watch: $cause") }
        .launchIn(this)
}
