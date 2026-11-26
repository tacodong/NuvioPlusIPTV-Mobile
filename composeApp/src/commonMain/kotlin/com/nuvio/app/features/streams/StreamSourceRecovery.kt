package com.nuvio.app.features.streams

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A usable network returned; selecting a VPN mode or an ordinary stats tick is not recovery. */
internal object StreamSourceRecovery {
    private val current = MutableStateFlow(0L)
    val revision = current.asStateFlow()

    init { observePlatformStreamRecovery(::networkRecovered) }

    fun networkRecovered() { current.update { it + 1 } }
}

internal expect fun observePlatformStreamRecovery(onRecovered: () -> Unit)
