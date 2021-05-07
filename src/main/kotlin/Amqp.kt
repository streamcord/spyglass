package io.streamcord.spyglass

import com.rabbitmq.client.Channel
import com.rabbitmq.client.ConnectionFactory
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

class AmqpSender private constructor(private val queueName: String, private val channel: Channel) {
    fun sendOnlineEvent(streamID: Long, userID: Long, timestamp: String) =
        send(AmqpEvent.StreamOnline(userID.toString(), streamID.toString(), timestamp))

    fun sendOfflineEvent(userID: Long, timestamp: String) = send(AmqpEvent.StreamOffline(userID.toString(), timestamp))

    private inline fun <reified T> send(obj: T) = sendText(Json.encodeToString(obj))

    private fun sendText(text: String) {
        try {
            channel.queueDeclarePassive(queueName)
        } catch (ex: IOException) {
            logger.warn("Encountered exception when checking if AMQP queue exists, attempting to redeclare", ex)
            channel.queueDeclare(queueName, true, false, false, null)
        }
        channel.basicPublish("", queueName, null, text.encodeToByteArray())
    }

    companion object {
        fun create(config: AppConfig.Amqp): Result<AmqpSender> = try {
            val factory = ConnectionFactory().apply {
                host = config.connection
                username = config.authentication?.username
                password = config.authentication?.password
            }

            val connection = factory.newConnection()
            val channel = connection.createChannel().apply {
                queueDeclare(config.queue, true, false, false, null)
            }

            Result.success(AmqpSender(config.queue, channel))
        } catch (ex: Throwable) {
            Result.failure(ex)
        }
    }
}

@Serializable
private sealed class AmqpEvent(@Required val op: Int) {
    @Required
    val v = 1

    @Serializable
    data class StreamOnline(val userID: String, val streamID: String, val time: String) : AmqpEvent(op = 1)

    @Serializable
    data class StreamOffline(val userID: String, val time: String) : AmqpEvent(op = 2)
}
