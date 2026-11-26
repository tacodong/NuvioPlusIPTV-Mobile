package com.nuvio.app.features.streams

import org.junit.Test
import kotlin.test.assertEquals

class StreamNetworkRecoveryStateTest {
    @Test fun initialAndDuplicateCallbacksDoNotRetrySources() {
        var recoveries = 0
        val state = StreamNetworkRecoveryState { recoveries++ }
        repeat(3) { state.update("wifi", true, false, true) }
        assertEquals(0, recoveries)
    }

    @Test fun validationReturningOnTheSameWifiRetriesOnce() {
        var recoveries = 0
        val state = StreamNetworkRecoveryState { recoveries++ }
        state.update("wifi", true, false, true)
        state.update("wifi", true, false, false)
        repeat(3) { state.update("wifi", true, false, true) }
        assertEquals(1, recoveries)
    }

    @Test fun internetReturningAndNetworkReplacementEachRetryOnce() {
        var recoveries = 0
        val state = StreamNetworkRecoveryState { recoveries++ }
        state.update("wifi", true, false, true)
        state.update("wifi", false, false, false)
        repeat(2) { state.update("wifi", true, false, true) }
        repeat(2) { state.update("ethernet", true, false, true) }
        assertEquals(2, recoveries)
    }

    @Test fun vpnInterfacesWaitForVerifiedVpnRecovery() {
        var recoveries = 0
        val state = StreamNetworkRecoveryState { recoveries++ }
        state.update("wifi", true, false, true)
        state.update("vpn", false, true, false)
        repeat(2) { state.update("vpn", true, true, true) }
        assertEquals(0, recoveries)
        repeat(2) { state.update("wifi", true, false, true) }
        assertEquals(1, recoveries)
    }
}
