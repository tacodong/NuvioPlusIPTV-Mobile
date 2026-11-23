package com.nuvio.app.features.profiles

import com.nuvio.app.core.storage.DesktopStorage

internal actual object ProfileStorage {
    // TODO(desktop): Update desktop VPN profile state when Nuvio profiles change.
    actual fun onActiveProfileChanged(id: Int) = Unit
    actual fun onProfileRemoved(id: Int) = Unit

    private val store = DesktopStorage.store("nuvio_profiles")

    actual fun loadPayload(): String? = store.getString("profiles")

    actual fun savePayload(payload: String) {
        store.putString("profiles", payload)
    }
}
