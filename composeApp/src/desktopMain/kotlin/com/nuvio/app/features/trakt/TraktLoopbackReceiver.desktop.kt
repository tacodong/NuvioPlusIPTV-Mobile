package com.nuvio.app.features.trakt

// Desktop retains the device-code flow for Trakt.
internal actual val traktLoopbackReceiverSupported: Boolean = false

internal actual suspend fun awaitTraktLoopbackRedirect(
    port: Int,
    path: String,
    timeoutMillis: Long,
    responseHtml: String,
): String? = null
