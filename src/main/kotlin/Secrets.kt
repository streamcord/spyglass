package io.streamcord.webhooks.server

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import java.io.File
import kotlin.system.exitProcess

private const val secretsStoreName = "secrets.yml"

fun fetchSecretByID(id: String): String {
    val secretsFile = File(secretsStoreName).takeIf { it.exists() } ?: run {
        System.err.println(
            """
            No $secretsStoreName file in current directory. Create one and format it as such:
            
            secrets:
                <first client ID>: <first client secret>
                <second client ID>: <second client secret>
                etc...
            """.trimIndent()
        )
        exitProcess(1)
    }

    return Yaml.default.decodeFromString<SecretsStore>(secretsFile.readText()).secrets[id] ?: run {
        System.err.println("No secret found in secrets file for client ID $id")
        exitProcess(2)
    }
}

@Serializable
private class SecretsStore(val secrets: Map<String, String>)
