package io.streamcord.webhooks.server

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoCollection
import org.bson.Document
import java.time.OffsetDateTime
import kotlin.system.exitProcess

suspend fun main() {
    val config = loadConfig()
    println("Config read. Starting up worker")

    val mongoClient: MongoClient = MongoClients.create(config.mongo.connection)
    val database = mongoClient.getDatabase(config.mongo.database)

    // fetch worker slots and update them with some error correction
    val workersCollection = database.getCollection(config.mongo.collections.workers).also {
        updateWorkerSlots(fetchAvailableClientIDs(), it)
    }

    // find a free worker slot. If there isn't one, report it and exit the program
    val freeSlot = workersCollection.find(Document("running", false)).firstOrNull() ?: run {
        System.err.println(
            """
            All currently available worker slots are running. 
            Add a new Twitch client ID and secret to the secret store before starting a new worker.
        """.trimIndent()
        )
        exitProcess(5)
    }

    workersCollection.updateOne(freeSlot, Document("\$set", Document("running", true)))

    val clientID = freeSlot["clientID"].toString()
    println("Starting worker with client ID [${clientID.ansiBold}]")

    val clientSecret = fetchSecretByID(clientID)
    println("Successfully loaded secret for client ID [${clientID.ansiBold}]")

    val (twitchClient, expiresIn) = TwitchClient.create(clientID, clientSecret)
    println("Created Twitch client with new access token. Expires in $expiresIn seconds")

    println()

    // synchronize subscriptions between DB and twitch
    val collection = database.getCollection(config.mongo.collections.subscriptions)
    println("Found subscriptions in DB. Count: ${collection.countDocuments(Document("clientID", clientID))}")
    syncSubscriptions(twitchClient, collection)
}

private val Any.ansiBold get() = "\u001B[1m$this\u001B[0m"

private fun updateWorkerSlots(clientIDs: Collection<String>, workers: MongoCollection<Document>) {
    // find any available IDs that aren't already registered and register them
    clientIDs.filter { !workers.find(Document("clientID", it)).any() }
        .map { Document("clientID", it).append("running", false).append("lastPulse", null) }
        .takeIf { it.isNotEmpty() }
        ?.also { workers.insertMany(it) }

    // check if any workers are registered but don't have secrets in the secrets file
    workers.find().map { it["clientID"].toString() }.filter { it !in clientIDs }.forEach {
        System.err.println("Secret for Twitch client ID $it not found, but this client ID is registered as a worker")
    }

    // if any "running" workers haven't pulsed in 20 seconds, mark them as not running
    workers.find(Document("running", true)).filter {
        val lastPulse = it["lastPulse"]?.toString()
        lastPulse == null || OffsetDateTime.parse(lastPulse).isBefore(OffsetDateTime.now().minusSeconds(20))
    }.forEach {
        workers.updateOne(it, Document("\$set", Document("running", false)))
    }
}

/**
 * Synchronizes subscriptions between Twitch and the database. If any subscriptions are reported by Twitch that aren't
 * in the database, they are added to the database. If any subscriptions are in the database that weren't reported by
 * Twitch, they are recreated and replaced in the database.
 */
private suspend fun syncSubscriptions(twitchClient: TwitchClient, collection: MongoCollection<Document>) {
    val twitchSubscriptions = twitchClient.fetchExistingSubscriptions().associateBy { it.id }
    println("Fetched existing subscriptions from Twitch. Count: ${twitchSubscriptions.size}")

    // create new subscriptions for any found in DB that weren't reported by Twitch
    collection.find(Document("clientID", twitchClient.clientID))
        .asSequence()
        .filter { it.getString("subID") !in twitchSubscriptions }
        .associateWith {
            twitchClient.createSubscription(it.getString("userID"), SubscriptionType.valueOf(it.getString("type")))
        }
        .mapValues { it.value?.toDocument()?.append("clientID", twitchClient.clientID) }
        .onEach { (old, new) -> new?.let { collection.replaceOne(old, new) } ?: collection.deleteOne(old) }
        .also { println("Created ${it.size.ansiBold} subscriptions found in DB but not reported by Twitch") }

    // store subscriptions that were reported by Twitch but weren't in the DB
    val storedSubscriptions = collection.find(Document("clientID", twitchClient.clientID))
        .associateBy { it.getString("subID") }

    twitchSubscriptions.values.asSequence()
        .filter { it.id !in storedSubscriptions }
        .onEach { collection.insertOne(it.toDocument().append("clientID", twitchClient.clientID)) }
        .also { println("Stored ${it.toList().size.ansiBold} subscriptions reported by Twitch but not in DB") }
}
