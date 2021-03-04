package io.streamcord.webhooks.server

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.reflect.typeOf

val safeJson = Json { ignoreUnknownKeys = true }

@OptIn(ExperimentalStdlibApi::class)
inline fun <reified T> Json.Default.safeDecodeFromString(text: String): T = try {
    decodeFromString(text)
} catch (ex: SerializationException) {
    System.err.println("Exception caught while deserializing from String to ${typeOf<T>().classifier?.toString()}. Printing stack trace and json.")
    System.err.println(text)
    ex.printStackTrace()
    safeJson.decodeFromString(text)
}
