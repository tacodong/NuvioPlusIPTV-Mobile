package com.nuvio.app.core.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import coil3.annotation.ExperimentalCoilApi
import coil3.network.ConnectivityChecker

/** Some working VPNs omit INTERNET; permit Coil to try them, as the TV host does. */
@OptIn(ExperimentalCoilApi::class)
internal fun imageConnectivityChecker(context: Context): ConnectivityChecker {
    val manager = context.getSystemService(ConnectivityManager::class.java)
        ?: return ConnectivityChecker.ONLINE
    return ConnectivityChecker {
        runCatching {
            val network = manager.activeNetwork
            val capabilities = network?.let(manager::getNetworkCapabilities)
            capabilities != null && (
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                )
        }.getOrDefault(true)
    }
}
