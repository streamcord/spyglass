package io.streamcord.webhooks.server

import com.rabbitmq.client.Channel
import com.rabbitmq.client.ConnectionFactory
import java.io.IOException

class AqmpSender private constructor(private val queueName: String, private val channel: Channel) {
    fun send(text: String) = channel.basicPublish("", queueName, null, text.encodeToByteArray())

    companion object {
        fun create(config: AppConfig.Aqmp): AqmpSender? = try {
            val factory = ConnectionFactory().apply {
                host = config.connection
            }

            val connection = factory.newConnection()
            val channel = connection.createChannel().apply {
                queueDeclare(config.queue, false, false, false, null)
            }

            AqmpSender(config.queue, channel)
        } catch (ex: IOException) {
            System.err.println("Failed to establish AQMP queue due to ${ex.stackTraceToString()}")
            null
        }
    }
}
