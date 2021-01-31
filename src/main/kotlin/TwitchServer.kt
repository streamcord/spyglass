package io.streamcord.webhooks.server

import io.ktor.application.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

fun createTwitchServer(aqmpConfig: AppConfig.Aqmp, onVerify: () -> Unit): CIOApplicationEngine? {
    val aqmpSender = AqmpSender.create(aqmpConfig) ?: return null

    return embeddedServer(CIO) {
        routing {
            post("webhooks/callback") {
                val text = call.receiveText()

                when (call.request.header("Twitch-Eventsub-Message-Type")) {
                    "webhook_callback_verification" -> {
                        val verificationBody = Json.decodeFromString<CallbackVerificationBody>(text)
                        call.respond(verificationBody.challenge)
                        onVerify()
                    }
                    "notification" -> aqmpSender.send(text)
                }
            }
        }
    }
}

@Serializable
class CallbackVerificationBody(val challenge: String, val subscription: SubscriptionData)
