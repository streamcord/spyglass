package io.streamcord.webhooks.server

import com.rabbitmq.client.Channel
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AqmpSender private constructor(private val queueName: String, private val channel: Channel) {
    fun sendOnlineEvent(streamID: String, userID: String) = send(AqmpEvent.StreamOnline(streamID, userID))

    fun sendOfflineEvent(userID: String) = send(AqmpEvent.StreamOffline(userID))

    private inline fun <reified T> send(obj: T) = send(Json.encodeToString(obj))

    private fun send(text: String) = channel.basicPublish("", queueName, null, text.encodeToByteArray())

    companion object {
        fun create(config: AppConfig.Aqmp): AqmpSender? = null
//            try {
//                val factory = ConnectionFactory().apply {
//                    host = config.connection
//                }
//
//                val connection = factory.newConnection()
//                val channel = connection.createChannel().apply {
//                    queueDeclare(config.queue, false, false, false, null)
//                }
//
//                AqmpSender(config.queue, channel)
//            } catch (ex: IOException) {
//                System.err.println("Failed to establish AQMP queue due to ${ex.stackTraceToString()}")
//                null
//            }
    }
}

private sealed interface AqmpEvent {
    val op: Int

    @Serializable
    data class StreamOnline(val streamId: String, val userID: String) : AqmpEvent {
        override val op = 1
    }

    @Serializable
    data class StreamOffline(val userId: String) : AqmpEvent {
        override val op = 2
    }
}
