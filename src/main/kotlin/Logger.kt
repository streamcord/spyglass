package io.streamcord.spyglass

import io.ktor.client.*
import io.ktor.client.engine.java.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

sealed interface Logger {
    fun fatal(exitCode: Int, text: String, ex: Throwable? = null): Nothing
    fun error(text: String, ex: Throwable? = null)
    fun warn(text: String, ex: Throwable? = null)
    fun info(text: String, ex: Throwable? = null)
    fun debug(text: String, ex: Throwable? = null)
    fun trace(text: String, ex: Throwable? = null)
}

class SimpleLogger : Logger {
    private val kotlinLogger = KotlinLogging.logger {}

    override fun fatal(exitCode: Int, text: String, ex: Throwable?): Nothing {
        kotlinLogger.error(text, ex)
        exitProcess(exitCode)
    }

    override fun error(text: String, ex: Throwable?) = kotlinLogger.error(text, ex)
    override fun warn(text: String, ex: Throwable?) = kotlinLogger.warn(text, ex)
    override fun info(text: String, ex: Throwable?) = kotlinLogger.info(text, ex)
    override fun debug(text: String, ex: Throwable?) = kotlinLogger.debug(text, ex)
    override fun trace(text: String, ex: Throwable?) = kotlinLogger.trace(text, ex)
}

class ComplexLogger(private val workerIndex: Long, config: AppConfig.Logging) : Logger {
    private val coroutineScope = CoroutineScope(newSingleThreadContext("SpyglassLogger"))
    private val kotlinLogger = KotlinLogging.logger {}
    private val client = config.error_webhook?.let { LoggerClient(it) }

    override fun fatal(exitCode: Int, text: String, ex: Throwable?): Nothing {
        kotlinLogger.error(text, ex)
        runBlocking {
            client?.postError(workerIndex, text, ex)
        }
        exitProcess(exitCode)
    }

    override fun error(text: String, ex: Throwable?) {
        kotlinLogger.error(text, ex)
        coroutineScope.launch {
            client?.postError(workerIndex, text, ex)
        }
    }

    override fun warn(text: String, ex: Throwable?) {
        kotlinLogger.error(text, ex)
        coroutineScope.launch {
            client?.postWarning(workerIndex, text, ex)
        }
    }

    override fun info(text: String, ex: Throwable?) = kotlinLogger.info(text, ex)
    override fun debug(text: String, ex: Throwable?) = kotlinLogger.debug(text, ex)
    override fun trace(text: String, ex: Throwable?) = kotlinLogger.trace(text, ex)
}

private class LoggerClient(val webhookAddress: String) {
    private val httpClient = HttpClient(Java) {
        expectSuccess = false
    }

    suspend fun postError(workerIndex: Long, text: String, ex: Throwable?) =
        post(workerIndex, text, true, ex)

    suspend fun postWarning(workerIndex: Long, text: String, ex: Throwable?) =
        post(workerIndex, text, false, ex)

    private suspend fun post(workerIndex: Long, text: String, isError: Boolean, ex: Throwable?) {
        val color = if (isError) 0xf44336 else 0xffc107
        val title = if (isError) "Spyglass Error" else "Spyglass Warning"
        val field = ex?.let {
            val formattedStackTrace = """
                ```
                $it
                ```
            """.trimIndent()
            listOf(WebhookBody.Embed.Field("Exception", formattedStackTrace))
        }
        val footer = WebhookBody.Embed.Footer("Worker $workerIndex")
        val embed = WebhookBody.Embed(title, text, field, footer, color)

        httpClient.post<HttpResponse>(webhookAddress) {
            contentType(ContentType.Application.Json)
            println(body)
            body = Json.encodeToString(WebhookBody(listOf(embed)))
        }.also { println(it.status) }
    }
}

@Serializable
private data class WebhookBody(val embeds: List<Embed>) {
    @Serializable
    data class Embed(
        val title: String, val description: String,
        val fields: List<Field>?, val footer: Footer, val color: Int
    ) {
        @Required
        val timestamp: String = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT)

        @Serializable
        data class Field(val name: String, val value: String)

        @Serializable
        data class Footer(val text: String)
    }
}
