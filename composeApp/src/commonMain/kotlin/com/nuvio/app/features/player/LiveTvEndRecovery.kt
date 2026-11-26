package com.nuvio.app.features.player

import com.nuvio.app.features.livetv.data.LiveTvPreferences
import com.nuvio.app.features.livetv.model.LiveTvLoadReport
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import kotlin.time.TimeSource

/** A clean EOF is a dropped connection for a continuous channel, not the end of a movie. */
internal class LiveTvEndRecovery {
    private val started = TimeSource.Monotonic.markNow()
    private var playingSinceMs: Long? = null
    private var lastPlayingSampleMs: Long? = null
    private var lastPlayingPositionMs: Long? = null
    private var playingProgressMs = 0L
    private var handledGeneration: Int? = null
    private var attempts = 0

    internal data class Retry(val attempt: Int, val delayMs: Long)

    fun observe(generation: Int, snapshot: PlayerPlaybackSnapshot, nowMs: Long = started.elapsedNow().inWholeMilliseconds): Retry? {
        if (snapshot.isPlaying) {
            val since = playingSinceMs ?: nowMs.also { playingSinceMs = it }
            val previousSample = lastPlayingSampleMs
            val previousPosition = lastPlayingPositionMs
            if (previousSample != null && previousPosition != null) {
                // Sliding live windows can move backwards. Count forward playback rather
                // than position from the first sample; timestamp jumps cannot buy health.
                val elapsed = (nowMs - previousSample).coerceAtLeast(0L)
                val advanced = (snapshot.positionMs - previousPosition).coerceIn(0L, elapsed)
                playingProgressMs = (playingProgressMs + advanced).coerceAtMost(HEALTHY_PLAYBACK_MS)
            }
            lastPlayingSampleMs = nowMs
            lastPlayingPositionMs = snapshot.positionMs
            if (nowMs - since >= HEALTHY_PLAYBACK_MS && playingProgressMs >= HEALTHY_PLAYBACK_MS) attempts = 0
        } else clearPlayingProgress()
        if (!snapshot.isEnded || handledGeneration == generation || attempts >= 6) return null
        handledGeneration = generation
        return Retry(++attempts, (attempts * 2_000L).coerceAtMost(10_000L))
    }

    fun handled(generation: Int): Boolean = handledGeneration == generation

    fun cancel(resetAttempts: Boolean) {
        handledGeneration = null
        clearPlayingProgress()
        if (resetAttempts) attempts = 0
    }

    private fun clearPlayingProgress() {
        playingSinceMs = null
        lastPlayingSampleMs = null
        lastPlayingPositionMs = null
        playingProgressMs = 0L
    }

    private companion object {
        const val HEALTHY_PLAYBACK_MS = 10_000L
    }
}

internal fun PlayerScreenRuntime.cancelLiveEndReconnect(resetAttempts: Boolean = false) {
    liveEndReconnectJob?.cancel()
    liveEndReconnectJob = null
    liveEndRecovery.cancel(resetAttempts)
}

internal fun PlayerScreenRuntime.reopenLiveSource() {
    activeInitialPositionMs = 0L
    activeInitialProgressFraction = null
    initialSeekApplied = true
    initialLoadCompleted = false
    errorMessage = null
    // A new native owner has no track selections, even when it exposes an identical list.
    trackPreferenceRestoreApplied = false
    preferredAudioSelectionApplied = false
    appliedAudioPreferences = null
    preferredSubtitleSelectionApplied = false
    isUserExplicitAudioSelection = false
    isUserExplicitSubtitleSelection = false
    hasScannedTextTracksOnce = false
    playerController = null
    playerControllerSourceUrl = null
    playbackSnapshot = PlayerPlaybackSnapshot()
    playbackSnapshotKey = null
    livePlaybackGeneration++
}

/** Called by both native engines' snapshot callback, including EOFs with no onError callback. */
internal fun PlayerScreenRuntime.handleLivePlaybackEnd(
    snapshot: PlayerPlaybackSnapshot,
    playbackKey: PlaybackKey,
    generation: Int,
    autoReconnect: () -> Boolean = { LiveTvPreferences.settings.value.autoReconnect },
    nowMs: Long? = null,
) {
    if (playbackKey != activePlaybackKey || generation != livePlaybackGeneration) return
    if (!isContinuousLivePlayback || !shouldPlay || !autoReconnect()) {
        cancelLiveEndReconnect()
        if (snapshot.isEnded) {
            shouldPlay = false
            controlsVisible = !playerControlsLocked
        }
        return
    }
    val retry = if (nowMs == null) liveEndRecovery.observe(generation, snapshot)
        else liveEndRecovery.observe(generation, snapshot, nowMs)
    if (!snapshot.isEnded) return
    if (retry != null) {
        LiveTvLoadReport.add("Live TV: connection ended; reconnecting (try ${retry.attempt})")
        liveEndReconnectJob = scope.launch {
            delay(retry.delayMs)
            if (playbackKey != activePlaybackKey || generation != livePlaybackGeneration ||
                !shouldPlay || !isContinuousLivePlayback || !autoReconnect()) return@launch
            // Recreate the owner instead of preparing its completed source again. That also
            // discards its finite buffered file and prevents a Range/resume at the old EOF.
            reopenLiveSource()
            liveEndReconnectJob = null
        }
    } else if (!liveEndRecovery.handled(generation)) {
        shouldPlay = false
        controlsVisible = !playerControlsLocked
        // Shown at once, then in the phone's language as soon as the text is read.
        val lost = "Lost the connection to this live channel. Select play or choose another channel."
        errorMessage = lost
        scope.launch {
            runCatching { getString(Res.string.livetv_error_connection_lost) }.getOrNull()?.let {
                if (errorMessage == lost) errorMessage = it
            }
        }
        LiveTvLoadReport.add("Live TV: stopped after six reconnects without sustained playback")
    }
}
