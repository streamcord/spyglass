package io.streamcord.spyglass

import com.mongodb.client.MongoClients
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.Filters
import com.mongodb.client.model.changestream.ChangeStreamDocument
import com.mongodb.client.model.changestream.OperationType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.bson.BsonDocument
import org.bson.Document

class DatabaseController private constructor(
    val subscriptions: MongoCollection<Document>,
    val notifications: MongoCollection<Document>
) {
    companion object {
        fun create(config: AppConfig.Mongo): DatabaseController {
            val client = try {
                MongoClients.create("mongodb://${config.connection}")
            } catch (ex: IllegalArgumentException) {
                logger.fatal(
                    ExitCodes.INVALID_DB_CONNECTION_STRING,
                    "Invalid MongoDB connection string ${config.connection}",
                    ex
                )
            }

            val database = try {
                client.getDatabase(config.database)
            } catch (ex: IllegalArgumentException) {
                logger.fatal(ExitCodes.INVALID_DB_NAME, "Invalid MongoDB database name ${config.database}", ex)
            }

            val subscriptions = database.tryGetCollection(config.collections.subscriptions)
            val notifications = database.tryGetCollection(config.collections.notifications)

            return DatabaseController(subscriptions, notifications)
        }

        private fun MongoDatabase.tryGetCollection(collectionName: String) = try {
            getCollection(collectionName)
        } catch (ex: IllegalArgumentException) {
            logger.fatal(ExitCodes.INVALID_DB_COLLECTION_NAME, "Invalid MongoDB collection name $collectionName", ex)
        }
    }
}

fun MongoCollection<Document>.insertSubscription(clientID: ClientID, secret: String, data: SubscriptionData) {
    insertOne(document {
        append("client_id", clientID.value)
        append("sub_id", data.id)
        append("created_at", data.created_at)
        append("messages", listOf<String>())
        append("revoked", false)
        append("secret", secret)
        append("type", data.type)
        append("user_id", data.condition.broadcaster_user_id)
        append("verified", false)
        append("verified_at", null)
    })
}

fun MongoCollection<Document>.revokeSubscription(subID: String, timestamp: String, reason: String): Document? {
    return findOneAndUpdate(
        Document("sub_id", subID),
        setValues("revoked" to true, "revoked_at" to timestamp, "revocation_reason" to reason)
    )
}

fun MongoCollection<Document>.verifySubscription(subID: String, timestamp: String): Document? =
    findOneAndUpdate(Document("sub_id", subID), setValues("verified" to true, "verified_at" to timestamp))

private const val MESSAGES_TO_STORE = 50
fun MongoCollection<Document>.updateSubscription(subID: String, newMessageID: String): Document? =
    find(Document("sub_id", subID)).firstOrNull()?.apply {
        val deque = ArrayDeque<String>(getList("messages", String::class.java))
        if (deque.size >= MESSAGES_TO_STORE) repeat(deque.size - MESSAGES_TO_STORE + 1) {
            deque.removeLast()
        }
        deque.addFirst(newMessageID)
        updateOne(this, setValues("messages" to deque.toList()))
    }

inline fun document(init: Document.() -> Unit) = Document().apply(init)

private fun setValues(first: Pair<String, Any?>, vararg extra: Pair<String, Any?>) =
    Document("\$set", Document(first.first, first.second).apply {
        extra.forEach { append(it.first, it.second) }
    })

/*
 launches the watch operation as a flow and collects it in the passed coroutine scope. if an exception is caught, the
 watch is restarted immediately from where it left off. If an invalidate event is received, the change stream is
 restarted completely.
*/
fun MongoCollection<Document>.launchWatch(
    coroutineScope: CoroutineScope,
    operationTypes: Set<OperationType>,
    onEach: suspend (ChangeStreamDocument<Document>) -> Unit
) = coroutineScope.launch {
    val finalOperationTypes = (operationTypes + OperationType.INVALIDATE).map { it.value }
    val aggregates = listOf(Aggregates.match(Filters.`in`("operationType", finalOperationTypes)))

    var resumeToken: BsonDocument? = null
    var invalidated = false

    while (true) {
        try {
            val watch = watch(aggregates)

            resumeToken?.also {
                if (!invalidated) watch.resumeAfter(it)
                else watch.startAfter(it)
            }

            invalidated = false

            watch.asFlow().collect {
                resumeToken = it.resumeToken
                if (it.operationType == OperationType.INVALIDATE) {
                    invalidated = true
                    throw IllegalStateException("Database received invalidate event, restarting change stream")
                }
                onEach(it)
            }
        } catch (ex: Exception) {
            logger.warn("Exception in notifications change stream watch", ex)
            delay(5000)
        }
    }
}
