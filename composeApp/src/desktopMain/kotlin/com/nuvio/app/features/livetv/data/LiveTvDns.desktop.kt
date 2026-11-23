package com.nuvio.app.features.livetv.data

import com.nuvio.app.features.livetv.model.LiveChannel
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.HttpTimeout

internal actual object LiveTvDns {
    actual val supported: Boolean = true
    actual fun current(): DnsChoice = ForkDns.choice
    actual fun set(value: DnsChoice) = ForkDns.set(value)
    // TODO(desktop): Apply the configured DNS to mpv without rewriting HTTPS hostnames.
    actual fun noteChannels(channels: List<LiveChannel>) = Unit
}

/** The app's API client settings, plus redirects followed by Ktor and the provider lookup. */
internal actual fun createXtreamHttpClient(): HttpClient = HttpClient(OkHttp) {
    followRedirects = true
    expectSuccess = false
    install(HttpTimeout) {
        requestTimeoutMillis = 30_000
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 30_000
    }
    install(HttpRedirect) { allowHttpsDowngrade = true }
    engine {
        config {
            followRedirects(false)
            followSslRedirects(false)
            retryOnConnectionFailure(false)
            dns(ForkDns.provider)
        }
    }
}
