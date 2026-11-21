package com.nuvio.app.features.livetv.sports

import com.nuvio.app.features.livetv.data.createXtreamHttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import platform.Foundation.NSLocale
import platform.Foundation.countryCode
import platform.Foundation.currentLocale
import platform.Foundation.languageCode
import platform.posix.usleep

internal actual object SportsPlatform {
    private val client by lazy { createXtreamHttpClient() }

    actual suspend fun get(url: String, headers: Map<String, String>): String {
        val response = client.get(url) {
            header(HttpHeaders.Accept, "application/json")
            headers.forEach { (name, value) ->
                if (!HttpHeaders.isUnsafe(name)) header(name, value)
            }
        }
        if (!response.status.isSuccess()) error("HTTP ${response.status.value}")
        return response.bodyAsText().takeIf { it.isNotBlank() } ?: error("Empty sports feed.")
    }

    actual fun countryCode(): String = NSLocale.currentLocale.countryCode.orEmpty().uppercase()
    actual fun languageCode(): String = NSLocale.currentLocale.languageCode.lowercase()
    actual fun pause(ms: Long) {
        if (ms > 0L) usleep(ms.coerceAtMost(999L).toUInt() * 1_000u)
    }

    actual fun foldAccents(text: String): String = SportsAccents.fold(text)
}
