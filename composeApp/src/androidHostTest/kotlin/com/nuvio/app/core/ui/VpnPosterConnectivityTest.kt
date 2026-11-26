package com.nuvio.app.core.ui

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.nuvio.app.NuvioApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkInfo
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercise the app's real Coil/Ktor loader, not a copied connectivity predicate. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = NuvioApplication::class)
class VpnPosterConnectivityTest {
    @Before fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val app = RuntimeEnvironment.getApplication()
        // The host-test library manifest lacks the Android app's permission. Without it Coil
        // deliberately assumes ONLINE, which would hide the production connectivity bug.
        shadowOf(app).grantPermissions(android.Manifest.permission.ACCESS_NETWORK_STATE)
        assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,
            app.checkSelfPermission(android.Manifest.permission.ACCESS_NETWORK_STATE))
    }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun workingVpnWithoutInternetCapabilityStillDownloadsPosters() {
        assertPosterRequest(ConnectivityManager.TYPE_VPN, hasInternet = false, expectedOnline = true)
    }

    @Test fun ordinaryWifiStillDownloadsPosters() {
        assertPosterRequest(ConnectivityManager.TYPE_WIFI, hasInternet = true, expectedOnline = true)
    }

    @Test fun noActiveNetworkDoesNotStartPosterRequests() {
        assertPosterRequest(null, hasInternet = false, expectedOnline = false)
    }

    private fun assertPosterRequest(type: Int?, hasInternet: Boolean, expectedOnline: Boolean) = runBlocking {
        val app = RuntimeEnvironment.getApplication() as NuvioApplication
        val manager = app.getSystemService(ConnectivityManager::class.java)
        val shadow = shadowOf(manager)
        if (type == null) {
            shadow.setActiveNetworkInfo(null)
        } else {
            val info = ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, type, 0, true, true)
            shadow.setActiveNetworkInfo(info)
            val network = requireNotNull(manager.activeNetwork)
            val capabilities = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
            shadowOf(capabilities).addTransportType(
                if (type == ConnectivityManager.TYPE_VPN) NetworkCapabilities.TRANSPORT_VPN else NetworkCapabilities.TRANSPORT_WIFI,
            )
            if (hasInternet) shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            shadow.setNetworkCapabilities(network, capabilities)
            assertEquals(hasInternet, requireNotNull(manager.getNetworkCapabilities(network))
                .hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "image/svg+xml")
                .setBody("""<svg xmlns="http://www.w3.org/2000/svg" width="2" height="2"><rect width="2" height="2" fill="red"/></svg>"""))
            val loader = app.newImageLoader(app)
            try {
                val result = loader.execute(ImageRequest.Builder(app).data(server.url("/poster.svg").toString())
                    .size(2, 2).memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED).build())
                if (expectedOnline) assertTrue(result is SuccessResult, "Poster load failed: ${(result as? ErrorResult)?.throwable}")
                else assertTrue(result is ErrorResult)
                assertEquals(if (expectedOnline) 1 else 0, server.requestCount)
            } finally { loader.shutdown() }
        }
    }
}
