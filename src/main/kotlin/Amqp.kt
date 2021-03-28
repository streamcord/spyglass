package io.streamcord.spyglass

import com.rabbitmq.client.Channel
import com.rabbitmq.client.ConnectionFactory
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

sealed interface Sender {
    fun sendOnlineEvent(streamID: String, userID: String, userName: String)

    fun sendOfflineEvent(userID: String, userName: String)

    class Amqp private constructor(private val queueName: String, private val channel: Channel) : Sender {
        override fun sendOnlineEvent(streamID: String, userID: String, userName: String) = send(AmqpEvent.StreamOnline(streamID, userID))

        override fun sendOfflineEvent(userID: String, userName: String) = send(AmqpEvent.StreamOffline(userID))

        private inline fun <reified T> send(obj: T) = send(Json.encodeToString(obj))

        private fun send(text: String) = channel.basicPublish("", queueName, null, text.encodeToByteArray())

        companion object {
            fun create(config: AppConfig.Amqp): Amqp? =
                try {
                    val factory = ConnectionFactory().apply {
                        host = config.connection
                    }

                    val connection = factory.newConnection()
                    val channel = connection.createChannel().apply {
                        queueDeclare(config.queue, false, false, false, null)
                    }

                    Amqp(config.queue, channel)
                } catch (ex: IOException) {
                    logger.warn("Failed to establish AMQP queue due to ${ex.stackTraceToString()}")
                    null
                }
        }
    }

    object Logger : Sender {
        override fun sendOnlineEvent(streamID: String, userID: String, userName: String) {
            logger.info("Stream by user $userName (user ID $userID) started with stream ID $streamID")
        }

        override fun sendOfflineEvent(userID: String, userName: String) {
            logger.info("Stream by user $userName (user ID $userID) ended")
        }
    }
}

private sealed interface AmqpEvent {
    val op: Int

    @Serializable
    data class StreamOnline(val streamId: String, val userID: String) : AmqpEvent {
        override val op = 1
    }

    @Serializable
    data class StreamOffline(val userId: String) : AmqpEvent {
        override val op = 2
    }
}
