package com.nuvio.app.features.streams

import android.app.Application
import com.nuvio.app.features.servers.ServerStreamSource
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertSame
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StreamSearchSessionsTest {
    private var loads = 0

    @Before
    fun reset() = runBlocking {
        StreamSearchSessions.clear()
        loads = 0
    }

    @Test
    fun aSecondSearchForTheSameEpisodeReusesTheResults() = runBlocking {
        val first = StreamSearchSessions.search(PROFILE, targets(), forceRefresh = false).toList()
        val second = StreamSearchSessions.search(PROFILE, targets(), forceRefresh = false).toList()

        assertEquals(1, loads)
        assertEquals(first, second)
    }

    @Test
    fun forceRefreshStartsANewSearch() = runBlocking {
        StreamSearchSessions.search(PROFILE, targets(), forceRefresh = false).toList()
        StreamSearchSessions.search(PROFILE, targets(), forceRefresh = true).toList()

        assertEquals(2, loads)
    }

    @Test
    fun anotherEpisodeIsASeparateSearch() = runBlocking {
        StreamSearchSessions.search(PROFILE, targets(episode = 1), forceRefresh = false).toList()
        StreamSearchSessions.search(PROFILE, targets(episode = 2), forceRefresh = false).toList()

        assertEquals(2, loads)
    }

    @Test
    fun anotherProfileIsASeparateSearch() = runBlocking {
        StreamSearchSessions.search(PROFILE, targets(), forceRefresh = false).toList()
        StreamSearchSessions.search(PROFILE + 1, targets(), forceRefresh = false).toList()

        assertEquals(2, loads)
    }

    @Test
    fun aChangedSourceSetupIsASeparateSearch() = runBlocking {
        StreamSearchSessions.search(PROFILE, targets(), forceRefresh = false).toList()
        StreamSearchSessions.search(PROFILE, targets(sourceId = "server:other"), forceRefresh = false).toList()

        assertEquals(2, loads)
    }

    @Test
    fun recoveryRetriesOnlyTheFailedSourceAndKeepsTheSuccessfulResult() = runBlocking {
        var healthyLoads = 0
        var failedLoads = 0
        var offline = true
        val good = stream("server:good")
        val request = targets().withServers(
            ServerStreamSource("server:good", "Good", false) { healthyLoads++; listOf(good) },
            ServerStreamSource("server:retry", "Retry", false) {
                failedLoads++
                if (offline) throw IOException("synthetic offline")
                listOf(stream("server:retry"))
            },
        )
        StreamSearchSessions.search(PROFILE, request, false).toList()
        offline = false
        StreamSourceRecovery.networkRecovered()
        val recovered = StreamSearchSessions.search(PROFILE, request, false).toList()
            .filterIsInstance<StreamLoadCompletion.Addon>()

        assertEquals(1, healthyLoads)
        assertEquals(2, failedLoads)
        assertEquals(2, recovered.sumOf { it.group.streams.size })
        assertSame(good, recovered.single { it.group.addonId == "server:good" }.group.streams.single())
    }

    @Test
    fun recoveryDoesNotInvalidateAHealthyCachedSearch() = runBlocking {
        val first = StreamSearchSessions.search(PROFILE, targets(), false).toList()
        StreamSourceRecovery.networkRecovered()
        val second = StreamSearchSessions.search(PROFILE, targets(), false).toList()
        assertEquals(1, loads)
        assertEquals(first, second)
    }

    @Test
    fun simultaneousReadersShareOneRetryForEachRecoveryIncludingOneDuringARetry() = runBlocking {
        var retryLoads = 0
        val retryStarted = CompletableDeferred<Unit>()
        val releaseRetry = CompletableDeferred<Unit>()
        val latestRetryStarted = CompletableDeferred<Unit>()
        val releaseLatestRetry = CompletableDeferred<Unit>()
        val request = targets().withServers(
            ServerStreamSource("server:good", "Good", false) { listOf(stream("server:good")) },
            ServerStreamSource("server:retry", "Retry", false) {
                retryLoads++
                if (retryLoads == 2) { retryStarted.complete(Unit); releaseRetry.await() }
                if (retryLoads == 3) { latestRetryStarted.complete(Unit); releaseLatestRetry.await() }
                throw IOException("synthetic offline")
            },
        )
        StreamSearchSessions.search(PROFILE, request, false).toList()
        StreamSourceRecovery.networkRecovered()
        val first = async { StreamSearchSessions.search(PROFILE, request, false).toList() }
        retryStarted.await()
        StreamSourceRecovery.networkRecovered()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            StreamSearchSessions.search(PROFILE, request, false).toList()
        }
        releaseRetry.complete(Unit)
        first.await(); second.await()
        assertEquals(2, retryLoads)
        val latest = async { StreamSearchSessions.search(PROFILE, request, false).toList() }
        withTimeout(5_000) { latestRetryStarted.await() }
        val joined = async(start = CoroutineStart.UNDISPATCHED) {
            StreamSearchSessions.search(PROFILE, request, false).toList()
        }
        releaseLatestRetry.complete(Unit)
        latest.await(); joined.await()
        StreamSearchSessions.search(PROFILE, request, false).toList()
        assertEquals(3, retryLoads)
    }

    @Test
    fun aRecoveryDuringTheInitialSearchDoesNotCancelItAndCanRetryItsLateFailure() = runBlocking {
        var goodLoads = 0
        var retryLoads = 0
        var offline = true
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val request = targets().withServers(
            ServerStreamSource("server:good", "Good", false) { goodLoads++; listOf(stream("server:good")) },
            ServerStreamSource("server:retry", "Retry", false) {
                retryLoads++
                if (offline) {
                    started.complete(Unit)
                    release.await()
                    throw IOException("synthetic offline")
                }
                listOf(stream("server:retry"))
            },
        )
        val initial = async { StreamSearchSessions.search(PROFILE, request, false).toList() }
        started.await()
        StreamSourceRecovery.networkRecovered()
        val joined = async { StreamSearchSessions.search(PROFILE, request, false).toList() }
        release.complete(Unit)
        initial.await(); joined.await()
        assertEquals(1, retryLoads)
        offline = false
        StreamSearchSessions.search(PROFILE, request, false).toList()
        assertEquals(1, goodLoads)
        assertEquals(2, retryLoads)
    }

    private fun stream(sourceId: String) = StreamItem(
        name = "1080p", url = "https://example.invalid/$sourceId.mkv", addonName = sourceId, addonId = sourceId,
    )

    private fun StreamSearchTargets.withServers(vararg servers: ServerStreamSource) = StreamSearchTargets(
        type, videoId, season, episode, emptyList(), servers.toList(), emptyList(), debridConfiguration,
    )

    private fun targets(episode: Int = 1, sourceId: String = "server:test") = StreamSearchTargets(
        type = "series",
        videoId = "tt2026:1:$episode",
        season = 1,
        episode = episode,
        streamAddons = emptyList(),
        serverSources = listOf(
            ServerStreamSource(addonId = sourceId, addonName = "Server", preferred = false) {
                loads++
                listOf(StreamItem(name = "1080p", url = "https://example.com/$episode.mkv", addonName = "Server", addonId = sourceId))
            },
        ),
        pluginProviderGroups = emptyList(),
    )

    private companion object {
        const val PROFILE = 1
    }
}
