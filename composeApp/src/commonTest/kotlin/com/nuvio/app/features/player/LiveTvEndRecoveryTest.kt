package com.nuvio.app.features.player

import androidx.compose.ui.Modifier
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.livetv.data.LiveTvPreferences
import com.nuvio.app.features.livetv.model.CatchupInfo
import com.nuvio.app.features.livetv.model.EpgProgram
import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.model.LiveTvClock
import com.nuvio.app.features.livetv.player.LiveTvPlayback
import com.nuvio.app.features.livetv.sports.SportsVideos
import com.nuvio.app.features.livetv.sports.SportsAddonSource
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LiveTvEndRecoveryTest {
    private val ended = PlayerPlaybackSnapshot(isLoading = false, isEnded = true, positionMs = 35_000L, durationMs = 35_000L)

    @Test fun cleanEofReopensTheSameLiveSourceFromTheBeginning() = runTest {
        val runtime = liveRuntime().apply {
            activeInitialPositionMs = 35_000L
            activeInitialProgressFraction = 1f
        }
        val originalKey = runtime.activePlaybackKey
        eof(runtime)
        assertTrue(runtime.shouldPlay)
        advanceTimeBy(1_999L)
        runCurrent()
        assertEquals(0, runtime.livePlaybackGeneration)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(1, runtime.livePlaybackGeneration)
        assertEquals(originalKey, runtime.activePlaybackKey)
        assertEquals(0L, runtime.activeInitialPositionMs)
        assertNull(runtime.activeInitialProgressFraction)
        assertNull(runtime.playbackSnapshotKey)
        assertFalse(runtime.playbackSnapshot.isEnded)
    }

    @Test fun repeatedEndedCallbacksScheduleOnlyOneReopen() = runTest {
        val runtime = liveRuntime()
        repeat(30) { eof(runtime) }
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(1, runtime.livePlaybackGeneration)
        advanceTimeBy(20_000L)
        runCurrent()
        assertEquals(1, runtime.livePlaybackGeneration)
    }

    @Test fun aLateSnapshotFromTheOldOwnerCannotReopenTheNewOne() = runTest {
        val runtime = liveRuntime()
        val oldKey = runtime.activePlaybackKey
        eof(runtime)
        advanceTimeBy(2_000L)
        runCurrent()
        runtime.handleLivePlaybackEnd(ended, oldKey, 0, autoReconnect = { true })
        advanceTimeBy(20_000L)
        runCurrent()
        assertEquals(1, runtime.livePlaybackGeneration)
        assertNull(runtime.liveEndReconnectJob)
    }

    @Test fun repeatedShortConnectionsStopAfterSixBackedOffReopens() = runTest {
        val runtime = liveRuntime()
        for (attempt in 1..6) {
            eof(runtime)
            advanceTimeBy((attempt * 2_000L).coerceAtMost(10_000L))
            runCurrent()
            assertEquals(attempt, runtime.livePlaybackGeneration)
        }
        eof(runtime)
        assertFalse(runtime.shouldPlay)
        assertNotNull(runtime.errorMessage)
        assertNull(runtime.liveEndReconnectJob)
        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(6, runtime.livePlaybackGeneration)
    }

    @Test fun stablePlaybackRestoresTheRetryBudgetWithoutResettingItForShortBursts() {
        val policy = LiveTvEndRecovery()
        assertEquals(1, policy.observe(0, ended, 0L)?.attempt)
        for (position in 0L..9_000L step 1_000L) {
            policy.observe(1, playing(position), position)
        }
        assertEquals(2, policy.observe(1, ended, 10_000L)?.attempt)
        for (position in 0L..10_000L step 1_000L) {
            policy.observe(2, playing(position), 11_000L + position)
        }
        assertEquals(1, policy.observe(2, ended, 22_000L)?.attempt)
    }

    @Test fun twentyFourAndThirtyFiveSecondLiveChunksKeepRecoveringPastSixCloses() = runTest {
        for (duration in listOf(24_000L, 35_000L)) {
            val runtime = liveRuntime("healthy-$duration")
            repeat(10) { cycle ->
                for (position in 0L..duration step 1_000L) {
                    val snapshot = playing(position)
                    runtime.updatePlaybackSnapshot(snapshot)
                    runtime.handleLivePlaybackEnd(snapshot, runtime.activePlaybackKey, runtime.livePlaybackGeneration,
                        autoReconnect = { true }, nowMs = testScheduler.currentTime)
                    advanceTimeBy(1_000L)
                }
                eof(runtime, snapshot = ended.copy(positionMs = duration, durationMs = duration))
                // Successful chunks return to the first retry's delay, not an increasing penalty.
                advanceTimeBy(2_000L)
                runCurrent()
                assertEquals(cycle + 1, runtime.livePlaybackGeneration)
                assertTrue(runtime.shouldPlay)
                assertNull(runtime.errorMessage)
            }
        }
    }

    @Test fun playingFlagWithoutPositionProgressDoesNotRefreshTheRetryBudget() {
        val policy = LiveTvEndRecovery()
        for (generation in 0..6) {
            val start = generation * 40_000L
            for (offset in 0L..35_000L step 1_000L) policy.observe(generation, playing(0L), start + offset)
            val retry = policy.observe(generation, ended, start + 36_000L)
            if (generation < 6) assertEquals(generation + 1, retry?.attempt) else assertNull(retry)
        }
    }

    @Test fun slidingWindowPositionResetsDoNotDiscardHealthyForwardPlayback() {
        val policy = LiveTvEndRecovery()
        assertEquals(1, policy.observe(0, ended, 0L)?.attempt)
        for (time in 0L..24_000L step 1_000L) policy.observe(1, playing(time % 6_000L), time)
        assertEquals(1, policy.observe(1, ended, 25_000L)?.attempt)
    }

    @Test fun aTimestampJumpCannotReplaceSustainedPlaybackProgress() {
        val policy = LiveTvEndRecovery()
        assertEquals(1, policy.observe(0, ended, 0L)?.attempt)
        policy.observe(1, playing(0L), 0L)
        for (time in 1_000L..20_000L step 1_000L) policy.observe(1, playing(1_000_000L), time)
        assertEquals(2, policy.observe(1, ended, 21_000L)?.attempt)
    }

    @Test fun disabledAutoReconnectLeavesTheEndFinal() = runTest {
        val runtime = liveRuntime()
        eof(runtime, enabled = { false })
        assertFalse(runtime.shouldPlay)
        assertNull(runtime.liveEndReconnectJob)
        advanceTimeBy(20_000L)
        assertEquals(0, runtime.livePlaybackGeneration)
    }

    @Test fun disablingAutoReconnectDuringTheDelayPreventsTheRequest() = runTest {
        val runtime = liveRuntime()
        var enabled = true
        eof(runtime, enabled = { enabled })
        enabled = false
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(0, runtime.livePlaybackGeneration)
    }

    @Test fun pauseDuringTheDelayCancelsRecoveryAndDoesNotResumePlayback() = runTest {
        val runtime = liveRuntime()
        eof(runtime)
        runtime.shouldPlay = false
        runtime.cancelLiveEndReconnect()
        assertFalse(runtime.shouldPlay)
        assertNull(runtime.liveEndReconnectJob)
        advanceTimeBy(20_000L)
        runCurrent()
        assertEquals(0, runtime.livePlaybackGeneration)
        assertFalse(runtime.shouldPlay)
    }

    @Test fun anAlreadyPausedLiveSourceDoesNotStartAReconnect() = runTest {
        val runtime = liveRuntime().apply { shouldPlay = false }
        eof(runtime)
        advanceTimeBy(20_000L)
        runCurrent()
        assertEquals(0, runtime.livePlaybackGeneration)
        assertFalse(runtime.shouldPlay)
    }

    @Test fun channelChangeInvalidatesThePendingSourceEvenBeforeItsNextSnapshot() = runTest {
        val runtime = liveRuntime()
        eof(runtime)
        runtime.activeVideoId = "another-channel"
        runtime.activeSourceUrl = "https://example.test/live/user/password/2.ts"
        advanceTimeBy(20_000L)
        runCurrent()
        assertEquals(0, runtime.livePlaybackGeneration)
    }

    @Test fun disposalCancelsThePendingSource() = runTest {
        val runtime = liveRuntime()
        eof(runtime)
        runtime.cancelLiveEndReconnect(resetAttempts = true)
        runtime.scope.cancel()
        advanceTimeBy(20_000L)
        runCurrent()
        assertEquals(0, runtime.livePlaybackGeneration)
        assertNull(runtime.liveEndReconnectJob)
    }

    @Test fun movieRecordingAndHighlightEndsRemainFinal() = runTest {
        val movie = liveRuntime().apply { activeProviderAddonId = "movie-addon" }
        assertFinal(movie)
        val recording = liveRuntime("recording")
        LiveTvPlayback.noteRecording(recording.activeSourceUrl)
        assertFinal(recording)
        val clip = liveRuntime("highlight", SportsVideos.SOURCE_ID)
        assertFinal(clip)
    }

    @Test fun sportsAddonReplayLaunchedAsAnOrdinaryChannelStillEndsOnce() = runTest {
        // Completed-game replay cards use launchFor rather than the catch-up launch path.
        val replay = liveRuntime("completed-game", SportsAddonSource.SOURCE_ID)
        assertFalse(LiveTvPlayback.isCatchup(replay.activeSourceUrl))
        assertFinal(replay)
        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(0, replay.livePlaybackGeneration)
    }

    @Test fun catchupEndsRemainFinalWithTheirFiniteDuration() = runTest {
        val oldPreferences = LiveTvPreferences.rawData.value
        try {
            val source = channel("42").copy(catchup = CatchupInfo("xc", null, 3))
            val now = LiveTvClock.nowMs()
            val id = assertNotNull(LiveTvPlayback.prepareCatchup(source, EpgProgram(now - 7_200_000L, now - 3_600_000L, "Past show"), 1))
            val launch = assertNotNull(PlayerLaunchStore.get(id))
            val runtime = runtime(launch.sourceUrl, launch.providerAddonId).apply { scope = backgroundScope }
            assertTrue(LiveTvPlayback.isCatchup(runtime.activeSourceUrl))
            assertFinal(runtime)
            PlayerLaunchStore.remove(id)
        } finally {
            LiveTvPreferences.edit { it.clear(); it.putAll(oldPreferences) }
            NuvioToastController.dismiss()
        }
    }

    private fun TestScope.assertFinal(runtime: PlayerScreenRuntime) {
        assertFalse(runtime.isContinuousLivePlayback)
        eof(runtime)
        assertFalse(runtime.shouldPlay)
        assertEquals(35_000L, runtime.playbackSnapshot.durationMs)
        assertNull(runtime.liveEndReconnectJob)
    }

    private fun playing(position: Long) = PlayerPlaybackSnapshot(isLoading = false, isPlaying = true, positionMs = position)

    private fun TestScope.eof(runtime: PlayerScreenRuntime, enabled: () -> Boolean = { true }, snapshot: PlayerPlaybackSnapshot = ended) {
        runtime.updatePlaybackSnapshot(snapshot)
        runtime.handleLivePlaybackEnd(snapshot, runtime.activePlaybackKey, runtime.livePlaybackGeneration, enabled, testScheduler.currentTime)
        runCurrent()
    }

    private suspend fun TestScope.liveRuntime(id: String = "live", sourceId: String = "provider"): PlayerScreenRuntime {
        val launch = LiveTvPlayback.launchFor(channel(id, sourceId), 1)
        return runtime(launch.sourceUrl, launch.providerAddonId).apply { scope = backgroundScope }
    }

    private fun channel(id: String, sourceId: String = "provider") = LiveChannel(
        key = id, sourceId = sourceId, sourceName = "Synthetic", name = id,
        tvgId = null, tvgName = null, logo = null, groupId = "group", group = "Group", number = 1,
        url = "https://example.test/live/user/password/$id.ts",
    )

    private fun runtime(url: String, provider: String?) = PlayerScreenRuntime(PlayerScreenArgs(
        profileId = 1, title = "Test", sourceUrl = url, sourceAudioUrl = null,
        sourceHeaders = emptyMap(), sourceResponseHeaders = emptyMap(), streamType = null,
        providerName = "Synthetic", streamTitle = "Test", streamSubtitle = null,
        initialBingeGroup = null, pauseDescription = null, onBack = {}, onOpenInExternalPlayer = null,
        onOpenExternalUrl = null, modifier = Modifier, logo = null, poster = null, background = null,
        seasonNumber = null, episodeNumber = null, episodeTitle = null, episodeThumbnail = null,
        contentType = "livetv", videoId = "channel", parentMetaId = "channel", parentMetaType = "livetv",
        providerAddonId = provider, torrentInfoHash = null, torrentFileIdx = null, torrentFilename = null,
        torrentTrackers = emptyList(), initialPositionMs = 0L, initialProgressFraction = null,
    ))
}
