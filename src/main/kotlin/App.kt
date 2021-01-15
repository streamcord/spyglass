package io.streamcord.webhooks.server

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import org.bson.Document
import kotlin.system.exitProcess

suspend fun main(args: Array<String>) {
    if (args.size != 1) {
        System.err.println("Required args: <client_id>")
        exitProcess(1)
    }

    val (clientID) = args
    println("Starting worker with client ID [${clientID.ansiBold}]")

    val clientSecret = fetchSecretByID(clientID)
    println("Successfully loaded secret for client ID [${clientID.ansiBold}]")

    val (twitchClient, expiresIn) = TwitchClient.create(clientID, clientSecret)
    println("Created Twitch client with new access token. Expires in $expiresIn seconds")

    val twitchSubscriptions = twitchClient.fetchExistingSubscriptions().associateBy { it.id }
    println("Fetched existing subscriptions. Count: ${twitchSubscriptions.size}")

    val client: MongoClient = MongoClients.create()
    val database = client.getDatabase("eventsub")
    val collection = database.getCollection("subscriptions")

    // create new subscriptions for any found in DB that weren't reported by Twitch
    val newSubscriptions = collection.find(Document("clientID", clientID))
        .filter { it.getString("subID") !in twitchSubscriptions }
        .onEach { collection.findOneAndDelete(it) }
        .mapNotNull { twitchClient.createSubscription(it.getString("userID"), it.getString("type")) }
        .also { println("Created ${it.size.ansiBold} subscriptions found in DB but not reported by Twitch") }

    // add existing and new subscriptions to DB if they weren't present already
    val storedSubscriptions = collection.find(Document("clientID", clientID)).associateBy { it.getString("subID") }
    twitchSubscriptions.values.asSequence()
        .filter { it.id !in storedSubscriptions }
        .let { it + newSubscriptions }
        .onEach {
            val newDoc = Document("clientID", clientID)
                .append("subID", it.id)
                .append("userID", it.condition.broadcaster_user_id)
                .append("transport", it.transport.method)
                .append("version", it.version)
                .append("created", it.created_at)
            collection.insertOne(newDoc)
        }.also { println("Stored ${it.toList().size.ansiBold} subscriptions not previously present in DB") }
}

private val Any.ansiBold get() = "\u001B[1m$this\u001B[0m"
