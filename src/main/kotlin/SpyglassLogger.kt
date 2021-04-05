package io.streamcord.spyglass

import io.ktor.client.*
import io.ktor.client.engine.java.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.tinylog.Logger
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

@Suppress("NOTHING_TO_INLINE") // inlined for callsite in tinylog
class SpyglassLogger(private val workerIndex: Long, config: AppConfig.Logging) {
    @OptIn(ExperimentalCoroutinesApi::class)
    internal val coroutineScope = CoroutineScope(newSingleThreadContext("SpyglassLogger"))
    internal val client = config.error_webhook?.let { LoggerClient(it) }

    internal inline fun fatal(exitCode: Int, text: String, ex: Throwable? = null): Nothing {
        Logger.error(ex, text)
        runBlocking {
            client?.postError(workerIndex, text, ex)
        }
        exitProcess(exitCode)
    }

    internal inline fun error(text: String, ex: Throwable? = null) {
        Logger.error(ex, text)
        coroutineScope.launch {
            client?.postError(workerIndex, text, ex)
        }
    }

    internal inline fun warn(text: String, ex: Throwable? = null) {
        Logger.warn(ex, text)
        coroutineScope.launch {
            client?.postWarning(workerIndex, text, ex)
        }
    }

    internal inline fun info(text: String, ex: Throwable? = null) = Logger.info(ex, text)
    internal inline fun debug(text: String, ex: Throwable? = null) = Logger.debug(ex, text)
    internal inline fun trace(text: String, ex: Throwable? = null) = Logger.trace(ex, text)
}

@PublishedApi
internal class LoggerClient(private val webhookAddress: String) {
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
            body = Json.encodeToString(WebhookBody(listOf(embed)))
        }
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
