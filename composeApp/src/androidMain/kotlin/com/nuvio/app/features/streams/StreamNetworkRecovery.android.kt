package com.nuvio.app.features.streams

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.nuvio.vpn.android.VpnPlaybackProtection

internal actual fun observePlatformStreamRecovery(onRecovered: () -> Unit) {
    // StreamSourceRecovery is an application-lifetime singleton: exactly one registration.
    val lock = Any()
    var observedRevision = VpnPlaybackProtection.networkRecoveryRevision()
    fun vpnRecovered() {
        val changed = synchronized(lock) {
            val revision = VpnPlaybackProtection.networkRecoveryRevision()
            if (revision == observedRevision) false else {
                observedRevision = revision
                true
            }
        }
        if (changed) onRecovered()
    }
    VpnPlaybackProtection.observeNetworkRecovery(::vpnRecovered)
    // Close the read/registration race without emitting for an already-connected tunnel.
    vpnRecovered()
}

/** One process-lifetime default-network callback, independent of source-screen collectors. */
internal object StreamNetworkRecovery {
    private var started = false

    @Synchronized fun initialize(context: Context) {
        if (started) return
        val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
        val state = StreamNetworkRecoveryState { StreamSourceRecovery.networkRecovered() }
        fun updateDefaultNetwork() {
            runCatching {
                val network = manager.activeNetwork
                val capabilities = network?.let(manager::getNetworkCapabilities)
                state.update(
                    network?.toString(),
                    capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
                    capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
                    capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
                )
            }
        }
        updateDefaultNetwork()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = updateDefaultNetwork()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = updateDefaultNetwork()
            override fun onLost(network: Network) {
                if (manager.activeNetwork == network || manager.activeNetwork == null) {
                    state.update(null, false, false, false)
                } else updateDefaultNetwork()
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 24) manager.registerDefaultNetworkCallback(callback)
            else manager.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
        }.onSuccess { started = true }
    }
}

/** Initial/unchanged callbacks are quiet; an Android VPN interface alone is not readiness. */
internal class StreamNetworkRecoveryState(private val recovered: () -> Unit) {
    private var initialized = false
    private var network: String? = null
    private var available = false
    private var validated = false

    @Synchronized fun update(next: String?, hasInternet: Boolean, isVpn: Boolean, isValidated: Boolean) {
        val usable = next != null && (hasInternet || isVpn)
        val changed = next != network
        val wasAvailable = available
        val wasValidated = validated
        network = next
        available = usable
        validated = isValidated
        if (!initialized) { initialized = true; return }
        if (!isVpn && usable && (!wasAvailable || changed || (isValidated && !wasValidated))) recovered()
    }
}
