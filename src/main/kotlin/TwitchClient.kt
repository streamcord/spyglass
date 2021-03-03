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
import kotlin.system.exitProcess

class TwitchClient private constructor(
    val clientID: ClientID, private val clientSecret: ClientSecret, private val callbackUri: String,
    private var accessTokenInfo: ResponseBody.AppAccessToken
) {
    private val httpClient = HttpClient(Java)
    private val callbackVerification = CompletableDeferred<Unit>()
    private var awaitingToken: CompletableDeferred<Unit>? = null

    fun verifyCallback() = callbackVerification.complete(Unit)

    suspend fun fetchExistingSubscriptions(): List<SubscriptionData> {
        awaitingToken?.await()

        val response = httpClient.get<HttpResponse>("https://api.twitch.tv/helix/eventsub/subscriptions") {
            withDefaults()
        }

        // if unauthorized, get a new access token and store it, then rerun the request
        if (response.status == HttpStatusCode.Unauthorized) {
            refetchToken()
            return fetchExistingSubscriptions()
        }

        if (!response.status.isSuccess()) {
            System.err.println("Failed to fetch subscriptions from Twitch. Error ${response.status}")
            exitProcess(3)
        }

        return Json.decodeFromString<ResponseBody.GetSubs>(response.readText()).data
    }

    suspend fun createSubscription(userID: String, type: String): SubscriptionData? {
        awaitingToken?.await()

        val condition = RequestBody.CreateSub.Condition(userID)
        val transport = RequestBody.CreateSub.Transport("webhook", "https://$callbackUri", "bingusbingus")

        val response = httpClient.post<HttpResponse>("https://api.twitch.tv/helix/eventsub/subscriptions") {
            withDefaults()
            header("Content-Type", "application/json")
            body = Json.encodeToString(RequestBody.CreateSub(type, "1", condition, transport))
        }

        // if unauthorized, get a new access token and store it, then rerun the request
        if (response.status == HttpStatusCode.Unauthorized) {
            refetchToken()
            return createSubscription(userID, type)
        }

        return if (!response.status.isSuccess()) {
            System.err.println("Failed to create subscription for user ID $userID with type $type. ${response.readText()}")
            null
        } else {
            callbackVerification.await()
            Json.decodeFromString<ResponseBody.CreateSub>(response.readText()).data.first()
        }
    }

    suspend fun removeSubscription(subID: String) {
        awaitingToken?.await()

        val response = httpClient.delete<HttpResponse>("https://api.twitch.tv/helix/eventsub/subscriptions") {
            withDefaults()
            parameter("id", subID)
        }

        // if unauthorized, get a new access token and store it, then rerun the request
        if (response.status == HttpStatusCode.Unauthorized) {
            refetchToken()
            return removeSubscription(subID)
        }

        if (!response.status.isSuccess()) {
            System.err.println("Failed to delete subscription with ID $subID. Error ${response.readText()}")
        } else Unit
    }

    private fun HttpRequestBuilder.withDefaults() {
        expectSuccess = false
        header("Authorization", "Bearer ${accessTokenInfo.access_token}")
        header("Client-ID", clientID.value)
    }

    private suspend fun refetchToken() {
        // if we're already awaiting a token, return immediately to rerun the request
        if (awaitingToken != null) {
            return
        }

        println("Encountered 401 Unauthorized from Twitch, fetching new access token...")
        awaitingToken = CompletableDeferred()
        accessTokenInfo = httpClient.fetchAccessToken(clientID, clientSecret)
        awaitingToken?.complete(Unit)
        awaitingToken = null
    }

    companion object {
        suspend fun create(id: ClientID, secret: ClientSecret, callbackUri: String): Pair<TwitchClient, Long> =
            HttpClient(Java).use { httpClient ->
                val accessTokenInfo: ResponseBody.AppAccessToken = httpClient.fetchAccessToken(id, secret)
                TwitchClient(id, secret, callbackUri, accessTokenInfo) to accessTokenInfo.expires_in
            }

        private suspend fun HttpClient.fetchAccessToken(
            id: ClientID,
            secret: ClientSecret
        ): ResponseBody.AppAccessToken {
            val response = post<HttpResponse>("https://id.twitch.tv/oauth2/token") {
                expectSuccess = false
                parameter("client_id", id.value)
                parameter("client_secret", secret.value)
                parameter("grant_type", "client_credentials")
            }

            if (!response.status.isSuccess()) {
                System.err.println("Failed to fetch access token from Twitch. Error ${response.readText()}")
                exitProcess(2)
            }

            return Json.decodeFromString(response.readText())
        }
    }
}

inline class ClientSecret(val value: String)
inline class ClientID(val value: String)

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
