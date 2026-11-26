package com.nuvio.app.features.livetv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.player.LivePlayRequest
import com.nuvio.app.features.livetv.player.LiveTvPlayback
import com.nuvio.app.features.player.DesktopGuidePreviewSurface
import com.nuvio.app.features.player.PlayerEngineController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.livetv_tv_guide_preview_hint
import nuvio.composeapp.generated.resources.livetv_tv_guide_preview_paused
import nuvio.composeapp.generated.resources.livetv_tv_guide_preview_starting
import nuvio.composeapp.generated.resources.livetv_tv_guide_preview_unavailable
import org.jetbrains.compose.resources.stringResource

/**
 * Whether the guide's preview window is on (this Mac only, not synced). Off means no second
 * player is ever created: the info panel shows the channel's logo instead.
 */
internal object DesktopGuidePreviewSetting {
    private const val KEY = "guide_preview_enabled"
    private val store get() = DesktopStorage.store("livetv_desktop")
    // Off unless turned on: a preview decodes the whole stream (4K channels too) beside the guide.
    private val state = MutableStateFlow(runCatching { store.getBoolean(KEY) }.getOrNull() ?: false)
    val enabled: StateFlow<Boolean> = state.asStateFlow()

    fun set(enabled: Boolean) {
        state.value = enabled
        runCatching { store.putBoolean(KEY, enabled) }
    }
}

/**
 * One preview at a time, like the TV guide: a click on a channel starts it, and anything that
 * opens the full player first releases the preview's stream (providers often allow one).
 */
@Stable
internal class DesktopGuidePreviewState {
    var channel by mutableStateOf<LiveChannel?>(null)
        private set
    var failed by mutableStateOf(false)
    var pictureReady by mutableStateOf(false)
    /** Stopped after a while with no mouse movement over the guide. */
    var pausedIdle by mutableStateOf(false)
    internal var controller: PlayerEngineController? = null

    fun start(next: LiveChannel) {
        if (channel?.key == next.key) return
        release {}
        pausedIdle = false
        failed = false
        pictureReady = false
        channel = next
    }

    /** Stops the preview, then runs [then] once its stream is closed. */
    fun release(then: () -> Unit) {
        val active = controller
        controller = null
        channel = null
        pictureReady = false
        if (active == null) then() else active.releaseBeforeNavigation(onReleased = then, onReleaseFailed = { then() })
    }
}

/** The 16:9 preview window in the guide's info panel. */
@Composable
internal fun DesktopGuidePreviewWindow(state: DesktopGuidePreviewState) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(10.dp)
    Box(Modifier.fillMaxHeight().aspectRatio(16f / 9f).clip(shape).background(Color.Black)
        .testTag("desktop-tv-guide-preview"), contentAlignment = Alignment.Center) {
        val channel = state.channel
        var request by androidx.compose.runtime.remember(channel?.key) { mutableStateOf<LivePlayRequest?>(null) }
        if (channel != null) {
            LaunchedEffect(channel.key) {
                // The same link and headers the full player would use. Portal (Stalker) and DRM
                // channels need the full player's handling, so they don't preview.
                val resolved = runCatching { LiveTvPlayback.requestFor(channel) }.getOrNull()
                if (resolved == null || resolved.mediaPolicy != null || channel.drm != null) state.failed = true
                else request = resolved
            }
            // A native view sits above Compose, so a failed preview is removed, not covered.
            if (!state.failed) request?.let { req ->
                DesktopGuidePreviewSurface(
                    sourceUrl = req.url,
                    sourceHeaders = req.headers,
                    modifier = Modifier.matchParentSize(),
                    onControllerReady = { state.controller = it },
                    onSnapshot = { if (it.videoWidth > 0 && it.videoHeight > 0) state.pictureReady = true },
                    onError = { if (it != null) state.failed = true },
                )
            }
            LaunchedEffect(channel.key) {
                delay(20_000)
                if (!state.pictureReady) state.failed = true
            }
        }
        when {
            channel == null -> Text(stringResource(if (state.pausedIdle) Res.string.livetv_tv_guide_preview_paused
                else Res.string.livetv_tv_guide_preview_hint), Modifier.padding(8.dp),
                fontSize = 12.sp, color = tokens.colors.textSecondary, textAlign = TextAlign.Center)
            state.failed -> Text(stringResource(Res.string.livetv_tv_guide_preview_unavailable),
                Modifier.padding(8.dp), fontSize = 12.sp, color = tokens.colors.textSecondary, textAlign = TextAlign.Center)
            !state.pictureReady -> Text(stringResource(Res.string.livetv_tv_guide_preview_starting),
                Modifier.padding(8.dp), fontSize = 12.sp, color = tokens.colors.textSecondary)
        }
    }
}
