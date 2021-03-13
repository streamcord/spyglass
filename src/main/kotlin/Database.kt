package io.streamcord.spyglass

import com.mongodb.client.MongoCollection
import org.bson.Document
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

fun MongoCollection<Document>.insertSubscription(clientID: String, secret: String, data: SubscriptionData) {
    insertOne(document {
        append("client_id", clientID)
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

fun MongoCollection<Document>.updateSubscription(original: Document, newSubID: String) {
    updateOne(original, setValues("sub_id" to newSubID, "verified" to false, "verified_at" to null))
}

fun MongoCollection<Document>.verifySubscription(subID: String) : Document? {
    val currentTimestamp = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT)
    return findOneAndUpdate(Document("sub_id", subID), setValues("verified" to true, "verified_at" to currentTimestamp))
}

inline fun document(init: Document.() -> Unit) = Document().apply(init)

private fun setValues(first: Pair<String, Any?>, vararg extra: Pair<String, Any?>) =
    Document("\$set", Document(first.first, first.second).apply {
        extra.forEach { append(it.first, it.second) }
    })
