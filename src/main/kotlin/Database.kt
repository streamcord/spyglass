package io.streamcord.spyglass

import com.mongodb.client.MongoCollection
import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.Filters
import com.mongodb.client.model.changestream.ChangeStreamDocument
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import org.bson.Document

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

inline fun document(init: Document.() -> Unit) = Document().apply(init)

private fun setValues(first: Pair<String, Any?>, vararg extra: Pair<String, Any?>) =
    Document("\$set", Document(first.first, first.second).apply {
        extra.forEach { append(it.first, it.second) }
    })

fun MongoCollection<Document>.watch(
    vararg operationTypes: String,
    onEach: suspend (ChangeStreamDocument<Document>) -> Unit
): Flow<ChangeStreamDocument<Document>> {
    return watch(listOf(Aggregates.match(Filters.`in`("operationType", operationTypes.toList()))))
        .asFlow()
        .onEach { onEach(it) }
        .catch { cause -> logger.warn("Exception in notifications change stream watch: $cause") }
}
