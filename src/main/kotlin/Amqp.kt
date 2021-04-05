package io.streamcord.spyglass

import com.rabbitmq.client.Channel
import com.rabbitmq.client.ConnectionFactory
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

sealed interface Sender {
    fun sendOnlineEvent(streamID: Long, userID: Long, timestamp: String)

    fun sendOfflineEvent(userID: Long, timestamp: String)

    class Amqp private constructor(private val queueName: String, private val channel: Channel) : Sender {
        override fun sendOnlineEvent(streamID: Long, userID: Long, timestamp: String) =
            send(AmqpEvent.StreamOnline(userID, streamID, timestamp))

        override fun sendOfflineEvent(userID: Long, timestamp: String) =
            send(AmqpEvent.StreamOffline(userID, timestamp))

        private inline fun <reified T> send(obj: T) = send(Json.encodeToString(obj))

        private fun send(text: String) {
            try {
                channel.queueDeclarePassive(queueName)
            } catch (ex: IOException) {
                logger.warn("Encountered exception when checking if AMQP queue exists, attempting to redeclare", ex)
                channel.queueDeclare(queueName, true, false, false, null)
            }
            channel.basicPublish("", queueName, null, text.encodeToByteArray())
        }

        companion object {
            fun create(config: AppConfig.Amqp): Result<Amqp> = try {
                val factory = ConnectionFactory().apply {
                    host = config.connection
                    username = config.authentication?.username
                    password = config.authentication?.password
                }

                val connection = factory.newConnection()
                val channel = connection.createChannel().apply {
                    queueDeclare(config.queue, true, false, false, null)
                }

                Result.success(Amqp(config.queue, channel))
            } catch (ex: Throwable) {
                Result.failure(ex)
            }
        }
    }
}

@Serializable
private sealed class AmqpEvent(@Required val op: Int) {
    @Required
    val v = 1

    @Serializable
    data class StreamOnline(val userID: Long, val streamID: Long, val time: String) : AmqpEvent(op = 1)

    @Serializable
    data class StreamOffline(val userID: Long, val time: String) : AmqpEvent(op = 2)
}
