package io.streamcord.webhooks.server

import io.ktor.application.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.util.pipeline.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

fun createHttpServer(twitchClient: TwitchClient, aqmpConfig: AppConfig.Aqmp) {
    val aqmpSender = AqmpSender.create(aqmpConfig) ?: error("Failed to open AQMP sender")

    embeddedServer(Netty, port = 8080) {
        routing {
            post("webhooks/callback") {
                val text = call.receiveText()

                when (call.request.header("Twitch-Eventsub-Message-Type")) {
                    "webhook_callback_verification" -> {
                        val verificationBody = Json.decodeFromString<CallbackVerificationBody>(text)
                        call.respond(verificationBody.challenge)
                        twitchClient.verifyCallback()
                    }
                    "notification" -> handleNotification(aqmpSender, text)
                }
            }
        }
    }.start(wait = true)
}

private fun PipelineContext<Unit, ApplicationCall>.handleNotification(sender: AqmpSender, text: String) {
    val notification = Json.decodeFromString<TwitchNotification>(text)

    when (val subType = call.request.header("Twitch-Eventsub-Subscription-Type")) {
        "stream.online" -> {
            val eventData = Json.decodeFromJsonElement<TwitchEvent.StreamOnline>(notification.event)

            if (eventData.type == "live") {
                sender.sendOnlineEvent(eventData.id, eventData.broadcaster_user_id)
            }
        }
        "stream.offline" -> {
            val eventData = Json.decodeFromJsonElement<TwitchEvent.StreamOffline>(notification.event)
            sender.sendOfflineEvent(eventData.broadcaster_user_id)
        }
        else -> {
            System.err.println("Received notification for subscription type $subType, ignoring")
        }
    }
}

@Serializable
private data class TwitchNotification(val subscription: SubscriptionData, val event: JsonElement)

private interface TwitchEvent {
    @Serializable
    data class StreamOnline(
        val id: String,
        val broadcaster_user_id: String,
        val broadcaster_user_login: String,
        val broadcaster_user_name: String,
        val type: String,
        val started_at: String
    ) : TwitchEvent

    @Serializable
    data class StreamOffline(
        val broadcaster_user_id: String,
        val broadcaster_user_login: String,
        val broadcaster_user_name: String
    )
}

@Serializable
class CallbackVerificationBody(val challenge: String, val subscription: SubscriptionData)
