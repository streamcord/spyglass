package io.streamcord.webhooks.server

import io.ktor.application.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

fun createHttpServer(twitchClient: TwitchClient, aqmpConfig: AppConfig.Aqmp) {
    // val aqmpSender = AqmpSender.create(aqmpConfig) ?: return null

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
                    "notification" -> println(text)
                }
            }
        }
    }.start(wait = true)
}

@Serializable
class CallbackVerificationBody(val challenge: String, val subscription: SubscriptionData)
