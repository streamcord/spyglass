package io.streamcord.spyglass

import io.ktor.client.*
import io.ktor.client.engine.java.*
import io.ktor.client.features.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class TwitchClient private constructor(
    private val proxy: String,
    val clientID: ClientID,
    private val authToken: AuthToken,
    private val callbackUri: String
) {
    suspend fun awaitCallbackAccess(expectedStatus: HttpStatusCode) {
        logger.info("Testing callback URI in 10 seconds...")

        repeat(3) {
            delay(10000)

            val status = try {
                httpClient.get<HttpResponse>("https://$callbackUri/") { expectSuccess = false }.status
            } catch (ex: Throwable) {
                return@repeat logger.info("Attempt $it: Callback URI $callbackUri is inaccessible. When testing callback, an exception was thrown: ${ex.stackTraceToString()}")
            }

            if (status == expectedStatus) {
                return logger.info("Verified that callback URI $callbackUri is accessible")
            } else {
                return@repeat logger.info("Attempt $it: Callback URI $callbackUri is inaccessible. Expected $expectedStatus, received $status")
            }
        }

        logger.fatal(
            ExitCodes.INACCESSIBLE_CALLBACK,
            "Callback URI $callbackUri remained inaccessible after 3 connection attempts"
        )
    }

    suspend fun fetchExistingSubscriptions(): List<SubscriptionData> = fetchExistingSubscriptions(mutableListOf(), null)

    private tailrec suspend fun fetchExistingSubscriptions(
        list: MutableList<SubscriptionData>,
        cursor: String?
    ): List<SubscriptionData> {
        val response = httpClient.get<HttpResponse>("$proxy/helix/eventsub/subscriptions") {
            withDefaults()
            cursor?.let { parameter("after", it) }
        }

        // if unauthorized, get a new access token and store it, then rerun the request
        if (!response.status.isSuccess()) {
            logger.error("Failed to fetch subscriptions from Twitch. Error ${response.status}. Attempting refetch in 5 seconds")
            delay(5_000)
            return fetchExistingSubscriptions(list, cursor)
        }

        val body = Json.safeDecodeFromString<ResponseBody.GetSubs>(response.readText())
        list.addAll(body.data)

        return if (list.size >= body.total) {
            logger.info("Total subscriptions: ${body.total}")
            list
        } else {
            fetchExistingSubscriptions(list, body.pagination.getValue("cursor").jsonPrimitive.content)
        }
    }

    tailrec suspend fun createSubscription(userID: Long, type: String, secret: String): List<SubscriptionData>? {
        if (userID !in 0..Int.MAX_VALUE) {
            logger.warn("Attempted to create subscription for invalid user ID $userID")
            return null
        }

        val condition = RequestBody.CreateSub.Condition(userID.toString())
        val transport = RequestBody.CreateSub.Transport("webhook", "https://$callbackUri/webhooks/callback", secret)

        val response = httpClient.post<HttpResponse>("$proxy/helix/eventsub/subscriptions") {
            withDefaults()
            contentType(ContentType.Application.Json)
            body = Json.encodeToString(RequestBody.CreateSub(type, "1", condition, transport))
        }

        // if unauthorized, get a new access token and store it, then rerun the request
        return when {
            response.status == HttpStatusCode.InternalServerError || response.status == HttpStatusCode.BadGateway -> {
                logger.info("Failed to create subscription due to ${response.status}. Retrying in 30 seconds...")
                delay(30000)
                createSubscription(userID, type, secret)
            }
            !response.status.isSuccess() -> {
                logger.error("Failed to create subscription for user ID $userID with type $type. ${response.readText()}")
                null
            }
            else -> Json.safeDecodeFromString<ResponseBody.CreateSub>(response.readText()).data.onEach {
                logger.info("Created subscription with ID ${it.id} for user ID $userID and type $type")
            }
        }
    }

    tailrec suspend fun removeSubscription(subID: String): Boolean {
        val response = httpClient.delete<HttpResponse>("$proxy/helix/eventsub/subscriptions") {
            withDefaults()
            parameter("id", subID)
        }

        // if unauthorized, get a new access token and store it, then rerun the request
        return when {
            response.status == HttpStatusCode.InternalServerError || response.status == HttpStatusCode.BadGateway -> {
                logger.info("Failed to remove subscription due to ${response.status}. Retrying in 30 seconds...")
                delay(30000)
                removeSubscription(subID)
            }
            !response.status.isSuccess() -> {
                logger.error("Failed to remove subscription with ID $subID. Error ${response.readText()}")
                false
            }
            else -> {
                logger.info("Removed subscription with ID $subID")
                true
            }
        }
    }

    private fun HttpRequestBuilder.withDefaults() {
        expectSuccess = false
        header("Authorization", "Bearer $authToken")
        header("Client-ID", clientID.value)
    }

    companion object {
        private val httpClient: HttpClient by lazy { HttpClient(Java) }

        fun create(proxy: String, id: ClientID, secret: AuthToken, callbackUri: String): TwitchClient {
            return TwitchClient(proxy, id, secret, callbackUri)
        }
    }
}

@JvmInline
value class AuthToken(val value: String)

@JvmInline
value class ClientID(val value: String)

object ResponseBody {
    @Serializable
    data class GetSubs(
        val total: Long, val data: List<SubscriptionData>,
        val limit: Long? = null, val max_total_cost: Long, val total_cost: Long,
        val pagination: JsonObject
    )

    @Serializable
    data class CreateSub(
        val data: List<SubscriptionData>,
        val limit: Long? = null,
        val total: Long,
        val max_total_cost: Long,
        val total_cost: Long
    )
}

private object RequestBody {
    @Serializable
    data class CreateSub(val type: String, val version: String, val condition: Condition, val transport: Transport) {
        @Serializable
        data class Condition(val broadcaster_user_id: String)

        @Serializable
        data class Transport(val method: String, val callback: String, val secret: String)
    }
}

@Serializable
data class SubscriptionData(
    val id: String, val status: String, val type: String, val version: String,
    val condition: Condition,
    val created_at: String,
    val transport: Transport,
    val cost: Int? = null
) {
    @Serializable
    data class Condition(val broadcaster_user_id: Long)

    @Serializable
    data class Transport(val method: String, val callback: String, val batching: Boolean? = null)
}
