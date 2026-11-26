package com.nuvio.stalker

import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Synthetic protocol responses only; no network, provider account or Android state. */
class StalkerEndpointDiscoveryTest {
    private val config = StalkerConfig("https://portal.example.invalid/c/", "02:00:00:00:00:01")

    @Test fun refusedFirstLoginPathDoesNotHideAWorkingAlternative() = runBlocking {
        val requests = mutableListOf<Url>()
        val client = StalkerClient(config, StalkerTransport { url, headers ->
            val request = Url(url)
            requests += request
            if (request.encodedPath == "/portal.php") {
                StalkerHttpResponse(401, "", finalUrl = url)
            } else {
                when (request.parameters["action"]) {
                    "handshake" -> json("""{"token":"fixture-token"}""", url)
                    "get_profile" -> {
                        assertEquals("Bearer fixture-token", headers["Authorization"])
                        json("""{"id":"fixture-account","status":0}""", url)
                    }
                    "get_genres" -> json("""[{"id":"fixture-group","title":"Sports"}]""", url)
                    else -> error("Unexpected synthetic portal operation")
                }
            }
        }, { 0L })

        assertEquals(listOf("fixture-group"), client.liveGroups().map { it.id })
        assertEquals(listOf("/portal.php", "/server/load.php"),
            requests.filter { it.parameters["action"] == "handshake" }.map { it.encodedPath })
        assertEquals("/server/load.php", requests.last().encodedPath)
        assertEquals("get_genres", requests.last().parameters["action"])
    }

    @Test fun allRefusedLoginPathsRemainAnAuthenticationFailure() = runBlocking {
        val requestedPaths = mutableListOf<String>()
        val client = StalkerClient(config, StalkerTransport { url, _ ->
            requestedPaths += Url(url).encodedPath
            StalkerHttpResponse(401, "", finalUrl = url)
        }, { 0L })

        assertEquals(StalkerError.AUTHENTICATION_FAILED, failure { client.authenticate() }.error)
        assertEquals(listOf("/portal.php", "/server/load.php", "/stalker_portal/server/load.php"), requestedPaths)
    }

    @Test fun authenticationRefusalIsNotHiddenByMissingOrMalformedAlternatives() = runBlocking {
        val requestedPaths = mutableListOf<String>()
        val client = StalkerClient(config, StalkerTransport { url, _ ->
            val request = Url(url)
            requestedPaths += request.encodedPath
            assertEquals("handshake", request.parameters["action"])
            when (request.encodedPath) {
                "/portal.php" -> StalkerHttpResponse(401, "", finalUrl = url)
                "/server/load.php" -> StalkerHttpResponse(404, "", finalUrl = url)
                "/stalker_portal/server/load.php" -> StalkerHttpResponse(200, "not-json", finalUrl = url)
                else -> error("Unexpected synthetic login path")
            }
        }, { 0L })

        assertEquals(StalkerError.AUTHENTICATION_FAILED, failure { client.authenticate() }.error)
        assertEquals(listOf("/portal.php", "/server/load.php", "/stalker_portal/server/load.php"), requestedPaths)
    }

    @Test fun blockedProfileCannotFallThroughToAnUnrelatedSuccessfulLoginPath() = runBlocking {
        assertProfileFailureStopsDiscovery("""{"id":"fixture-account","status":1}""", StalkerError.ACCOUNT_BLOCKED)
    }

    @Test fun requiredProviderCredentialsCannotBeBypassedByTryingAnotherPath() = runBlocking {
        assertProfileFailureStopsDiscovery("""{"id":"fixture-account","status":2}""", StalkerError.AUTHENTICATION_REQUIRED)
    }

    @Test fun requiredDeviceIdentityCannotBeBypassedByTryingAnotherPath() = runBlocking {
        assertProfileFailureStopsDiscovery(
            """{"status":9,"error":"Device signature required"}""", StalkerError.DEVICE_AUTH_REQUIRED
        )
    }

    private suspend fun assertProfileFailureStopsDiscovery(profile: String, expected: StalkerError) {
        val requests = mutableListOf<Url>()
        val client = StalkerClient(config, StalkerTransport { url, _ ->
            val request = Url(url)
            requests += request
            when (request.parameters["action"]) {
                "handshake" -> json("""{"token":"fixture-token"}""", url)
                "get_profile" -> json(
                    if (request.encodedPath == "/portal.php") profile
                    else """{"id":"fixture-account","status":0}""", url
                )
                "get_genres" -> json("""[{"id":"fixture-group","title":"Sports"}]""", url)
                else -> error("Unexpected synthetic portal operation")
            }
        }, { 0L })

        assertEquals(expected, failure { client.liveGroups() }.error)
        assertEquals(listOf("/portal.php", "/portal.php"), requests.map { it.encodedPath })
        assertFalse(requests.any { it.parameters["action"] == "get_genres" })
    }

    private fun json(value: String, url: String) = StalkerHttpResponse(200, "{\"js\":$value}", finalUrl = url)

    private suspend fun failure(block: suspend () -> Unit): StalkerException {
        try { block() } catch (error: StalkerException) { return error }
        error("Expected a protocol failure")
    }
}
