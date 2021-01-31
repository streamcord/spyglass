package io.streamcord.webhooks.server

import io.ktor.client.*
import io.ktor.client.engine.java.*
import io.ktor.client.features.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.bson.Document
import kotlin.system.exitProcess

class TwitchClient private constructor(
    private val httpClient: HttpClient,
    val clientID: String, private val accessTokenInfo: ResponseBody.AppAccessToken
) {
    private val callbackVerification = CompletableDeferred<Unit>()

    fun verifyCallback() = callbackVerification.complete(Unit)

    suspend fun fetchExistingSubscriptions(): List<SubscriptionData> {
        val response = httpClient.get<HttpResponse>("https://api.twitch.tv/helix/eventsub/subscriptions") {
            withDefaults()
        }

        if (!response.status.isSuccess()) {
            System.err.println("Failed to fetch subscriptions from Twitch. Error ${response.status}")
            exitProcess(3)
        }

        return Json.decodeFromString<ResponseBody.GetSubs>(response.readText()).data
    }

    suspend fun createSubscription(userID: String, type: SubscriptionType): SubscriptionData? {
        val condition = RequestBody.CreateSub.Condition(userID)
        val transport = RequestBody.CreateSub.Transport("webhook", "https://localhost.com/callback", "bingusbingus")

        val response = httpClient.post<HttpResponse>("https://api.twitch.tv/helix/eventsub/subscriptions") {
            withDefaults()
            header("Content-Type", "application/json")
            body = Json.encodeToString(RequestBody.CreateSub(type.string, "1", condition, transport))
        }

        if (!response.status.isSuccess()) {
            System.err.println("Failed to create subscription for user ID $userID with type $type. ${response.readText()}")
            return null
        }

        callbackVerification.await()

        return Json.decodeFromString<ResponseBody.CreateSub>(response.readText()).data.first()
    }

    suspend fun removeSubscription(subID: String) {
        val response = httpClient.delete<HttpResponse>("https://api.twitch.tv/helix/eventsub/subscriptions") {
            withDefaults()
            parameter("id", subID)
        }

        if (!response.status.isSuccess()) {
            System.err.println("Failed to delete subscription with ID $subID. ${response.readText()}")
        }
    }

    private fun HttpRequestBuilder.withDefaults() {
        expectSuccess = false
        header("Authorization", "Bearer ${accessTokenInfo.access_token}")
        header("Client-ID", clientID)
    }

    companion object {
        suspend fun create(clientID: String, clientSecret: String): Pair<TwitchClient, Long> {
            val httpClient = HttpClient(Java)

            val response = httpClient.post<HttpResponse>("https://id.twitch.tv/oauth2/token") {
                expectSuccess = false
                parameter("client_id", clientID)
                parameter("client_secret", clientSecret)
                parameter("grant_type", "client_credentials")
            }

            if (!response.status.isSuccess()) {
                System.err.println("Failed to fetch access token from Twitch. Error ${response.readText()}")
                exitProcess(2)
            }

            val accessTokenInfo: ResponseBody.AppAccessToken = Json.decodeFromString(response.readText())
            return TwitchClient(httpClient, clientID, accessTokenInfo) to accessTokenInfo.expires_in
        }
    }
}

enum class SubscriptionType(val string: String) {
    ONLINE("stream.online"), OFFLINE("stream.offline");

    companion object {
        fun fromString(string: String) = values().find { it.string == string }
    }
}

object ResponseBody {
    @Serializable
    data class AppAccessToken(
        val access_token: String,
        val expires_in: Long,
        val token_type: String,
        val refresh_token: String? = null,
        val scope: List<String> = emptyList()
    )

    @Serializable
    data class GetSubs(val total: Int, val limit: Int, val data: List<SubscriptionData>, val pagination: JsonObject)

    @Serializable
    data class CreateSub(val data: List<SubscriptionData>, val limit: Int, val total: Int)
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
    val transport: Transport
) {
    @Serializable
    data class Condition(val broadcaster_user_id: String)

    @Serializable
    data class Transport(val method: String, val callback: String)
}

fun SubscriptionData.toDocument(): Document = Document("subID", id)
    .append("userID", condition.broadcaster_user_id)
    .append("transport", transport.method)
    .append("version", version)
    .append("created", created_at)
