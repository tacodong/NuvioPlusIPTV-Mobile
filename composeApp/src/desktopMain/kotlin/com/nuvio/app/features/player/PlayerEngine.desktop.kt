package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.AppPresenceState
import com.nuvio.app.core.ui.LocalNuvioPlatformDensity
import com.nuvio.app.core.ui.PresenceSnapshot
import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.nuvio.app.features.player.desktop.DesktopPlayerPictureInPicture
import com.nuvio.app.features.player.desktop.NativePlayerController
import com.nuvio.app.features.player.desktop.NativePlayerHost
import com.nuvio.app.features.player.desktop.desktopFullscreenChanges
import com.nuvio.app.features.autosync.AutoSyncMpvCoordinator
import com.nuvio.app.features.autosync.DesktopAutoSyncMpvPlatform
import com.nuvio.app.features.autosync.withAutoSyncMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop

@Composable
actual fun PlatformPlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String?,
    sourceHeaders: Map<String, String>,
    sourceResponseHeaders: Map<String, String>,
    stalkerMediaPolicy: com.nuvio.app.features.livetv.stalker.StalkerMediaPolicy?,
    externalSubtitles: List<com.nuvio.app.features.streams.StreamSubtitle>,
    streamType: String?,
    continuousLive: Boolean,
    useYoutubeChunkedPlayback: Boolean,
    modifier: Modifier,
    playWhenReady: Boolean,
    initialPositionMs: Long?,
    initialPositionRequestKey: String?,
    resizeMode: PlayerResizeMode,
    playbackEngine: com.nuvio.app.features.player.AndroidPlaybackEngine?,
    useNativeController: Boolean,
    playerControlsState: PlayerControlsState,
    onPlayerControlsAction: (PlayerControlsAction) -> Boolean,
    onPlayerControlsEvent: (String, Double) -> Boolean,
    onPlayerControlsScrubChange: (Long) -> Boolean,
    onPlayerControlsScrubFinished: (Long) -> Boolean,
    onInitialPositionHandled: (key: String, handled: Boolean) -> Unit,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
    sourceAvailable: Boolean,
) {
    // TODO(desktop): Route authenticated Stalker media through a per-request transport that
    // scopes credentials to their allowed origin, including playlist segments and redirects.
    if (stalkerMediaPolicy != null) {
        androidx.compose.runtime.LaunchedEffect(stalkerMediaPolicy) {
            onError("Authenticated Stalker playback is not yet supported on desktop.")
        }
        return
    }
    if (DesktopHostOs.current == DesktopHostOs.MACOS ||
        DesktopHostOs.current == DesktopHostOs.WINDOWS ||
        DesktopHostOs.current == DesktopHostOs.LINUX
    ) {
        NativePlayerSurface(
            sourceUrl = sourceUrl,
            sourceAvailable = sourceAvailable,
            sourceHeaders = sourceHeaders,
            externalSubtitles = externalSubtitles,
            modifier = modifier,
            playWhenReady = playWhenReady,
            resizeMode = resizeMode,
            initialPositionMs = if (continuousLive) 0L else initialPositionMs ?: 0L,
            initialPositionRequestKey = initialPositionRequestKey,
            playerControlsState = playerControlsState,
            onPlayerControlsAction = onPlayerControlsAction,
            onPlayerControlsEvent = onPlayerControlsEvent,
            onPlayerControlsScrubChange = onPlayerControlsScrubChange,
            onPlayerControlsScrubFinished = onPlayerControlsScrubFinished,
            onInitialPositionHandled = onInitialPositionHandled,
            onControllerReady = onControllerReady,
            onSnapshot = onSnapshot,
            onError = onError,
        )
        return
    }

    DesktopStubPlayerSurface(
        modifier = modifier,
        initialPositionRequestKey = initialPositionRequestKey,
        onInitialPositionHandled = onInitialPositionHandled,
        onControllerReady = onControllerReady,
        onSnapshot = onSnapshot,
    )
}

/**
 * The Live TV guide's preview window (macOS): a second, silent native player without the full
 * player's controls page, audio decode or large read-ahead.
 */
@Composable
internal fun DesktopGuidePreviewSurface(
    sourceUrl: String,
    sourceHeaders: Map<String, String>,
    modifier: Modifier,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
    /** Hidden but still playing: a menu is open over it (native views draw above Compose). */
    hidden: Boolean = false,
) {
    NativePlayerSurface(
        sourceUrl = sourceUrl,
        sourceAvailable = true,
        sourceHeaders = sourceHeaders,
        externalSubtitles = emptyList(),
        modifier = modifier,
        playWhenReady = true,
        resizeMode = PlayerResizeMode.Fit,
        initialPositionMs = 0L,
        initialPositionRequestKey = null,
        playerControlsState = PlayerControlsState(),
        onPlayerControlsAction = { false },
        onPlayerControlsEvent = { type, _ ->
            if (type == "guidePreviewError") {
                onError("Guide preview could not play")
                true
            } else false
        },
        onPlayerControlsScrubChange = { false },
        onPlayerControlsScrubFinished = { false },
        onInitialPositionHandled = { _, _ -> },
        onControllerReady = onControllerReady,
        onSnapshot = onSnapshot,
        onError = onError,
        guidePreview = true,
        guidePreviewHidden = hidden,
    )
}

@Composable
private fun NativePlayerSurface(
    sourceUrl: String,
    sourceAvailable: Boolean,
    sourceHeaders: Map<String, String>,
    externalSubtitles: List<com.nuvio.app.features.streams.StreamSubtitle>,
    modifier: Modifier,
    playWhenReady: Boolean,
    resizeMode: PlayerResizeMode,
    initialPositionMs: Long,
    initialPositionRequestKey: String?,
    playerControlsState: PlayerControlsState,
    onPlayerControlsAction: (PlayerControlsAction) -> Boolean,
    onPlayerControlsEvent: (String, Double) -> Boolean,
    onPlayerControlsScrubChange: (Long) -> Boolean,
    onPlayerControlsScrubFinished: (Long) -> Boolean,
    onInitialPositionHandled: (key: String, handled: Boolean) -> Unit,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
    guidePreview: Boolean = false,
    guidePreviewHidden: Boolean = false,
) {
    val platformDensity = LocalNuvioPlatformDensity.current
    val lastViewport = remember { DoubleArray(4) }
    val host = remember { NativePlayerHost().apply { if (guidePreview) isFocusable = false } }
    val controller = remember(host) { NativePlayerController(host, guidePreview = guidePreview) }
    if (guidePreview) {
        LaunchedEffect(guidePreviewHidden) {
            if (guidePreviewHidden) controller.setGuideViewport(0.0, 0.0, 0.0, 0.0)
            else if (lastViewport[2] > 1.0) controller.setGuideViewport(lastViewport[0], lastViewport[1], lastViewport[2], lastViewport[3])
        }
    }
    val hostFirstPaintComplete = remember { mutableStateOf(false) }
    val hostFirstFullSizePaintComplete = remember { mutableStateOf(false) }
    val playbackHeaders = remember(sourceHeaders) { sanitizePlaybackHeaders(sourceHeaders) }
    val latestExternalSubtitles = rememberUpdatedState(externalSubtitles)
    val latestPlayerControlsState = rememberUpdatedState(playerControlsState)
    val autoSyncScope = rememberCoroutineScope()
    // Kept across sources so its message count, part of the overlay's notification token, only grows.
    val autoSyncPlatform = remember {
        DesktopAutoSyncMpvPlatform(
            currentNotificationToken = { latestPlayerControlsState.value.notificationToken },
        )
    }
    val autoSyncCoordinator = remember(sourceUrl, playbackHeaders, autoSyncScope) {
        AutoSyncMpvCoordinator(
            scope = autoSyncScope,
            sourceUrl = sourceUrl,
            sourceHeaders = playbackHeaders,
            getSubtitleHeaders = { subtitleUrl ->
                latestExternalSubtitles.value
                    .firstOrNull { it.url == subtitleUrl }
                    ?.headers
                    .orEmpty()
            },
            getPreferredLanguage = { PlayerSettingsRepository.uiState.value.preferredSubtitleLanguage },
            platform = autoSyncPlatform,
        )
    }
    DisposableEffect(autoSyncCoordinator) {
        onDispose { autoSyncCoordinator.dispose() }
    }
    val readyController = remember(controller, autoSyncCoordinator) {
        // setSubtitleUri replaces the external subtitles, so the retimed copy takes the original's place.
        autoSyncCoordinator.wrap(controller) { _, path -> controller.setSubtitleUri(path) }
    }
    val controlsState = playerControlsState.withAutoSyncMessage(autoSyncPlatform.message)
    val latestControlsState = rememberUpdatedState(controlsState)
    val latestOnPlayerControlsAction = rememberUpdatedState(onPlayerControlsAction)
    val latestOnPlayerControlsEvent = rememberUpdatedState(onPlayerControlsEvent)
    val latestOnPlayerControlsScrubChange = rememberUpdatedState(onPlayerControlsScrubChange)
    val latestOnPlayerControlsScrubFinished = rememberUpdatedState(onPlayerControlsScrubFinished)
    val latestOnInitialPositionHandled = rememberUpdatedState(onInitialPositionHandled)
    val latestOnError = rememberUpdatedState(onError)
    val latestOnSnapshot = rememberUpdatedState(onSnapshot)
    val playerSettings by PlayerSettingsRepository.uiState.collectAsState()
    val decoderPriority = playerSettings.decoderPriority
    val nvidiaRtxSuperResolutionEnabled = playerSettings.nvidiaRtxSuperResolutionEnabled
    val nvidiaRtxHdrEnabled = playerSettings.nvidiaRtxHdrEnabled

    SideEffect {
        onControllerReady(readyController)
    }

    DisposableEffect(host) {
        DesktopPlayerPictureInPicture.setHost(host)
        DesktopPlayerPictureInPicture.setController(controller)
        host.onDisplayableChanged = { displayable ->
            if (!displayable) {
                hostFirstPaintComplete.value = false
                hostFirstFullSizePaintComplete.value = false
            }
        }
        host.onFirstPaint = {
            hostFirstPaintComplete.value = true
        }
        host.onFirstFullSizePaint = {
            hostFirstFullSizePaintComplete.value = true
        }
        onDispose {
            DesktopPlayerPictureInPicture.release()
            host.onDisplayableChanged = null
            host.onFirstPaint = null
            host.onFirstFullSizePaint = null
        }
    }

    LaunchedEffect(controller) {
        controller.setControlCallbacks(
            onAction = { action -> latestOnPlayerControlsAction.value(action) },
            onEvent = { type, value -> latestOnPlayerControlsEvent.value(type, value) },
            onScrubChange = { positionMs -> latestOnPlayerControlsScrubChange.value(positionMs) },
            onScrubFinished = { positionMs -> latestOnPlayerControlsScrubFinished.value(positionMs) },
        )
    }

    DisposableEffect(controller, sourceAvailable, sourceUrl, playbackHeaders) {
        onDispose { controller.dispose() }
    }

    // The controls overlay owns the player shortcuts. After alt-tab, desktop
    // window focus can return to the AWT/Compose host instead of the embedded
    // WebView, so explicitly hand keyboard focus back to the native controls
    // whenever the player window becomes active again.
    DisposableEffect(controller, hostFirstFullSizePaintComplete.value) {
        val uninstall = if (hostFirstFullSizePaintComplete.value) {
            controller.installWindowFocusForwarding()
        } else {
            null
        }
        onDispose { uninstall?.invoke() }
    }

    LaunchedEffect(
        controller,
        sourceAvailable,
        sourceUrl,
        playbackHeaders,
        decoderPriority,
        nvidiaRtxSuperResolutionEnabled,
        nvidiaRtxHdrEnabled,
        hostFirstFullSizePaintComplete.value,
        initialPositionMs,
        initialPositionRequestKey,
    ) {
        if (!sourceAvailable || !hostFirstFullSizePaintComplete.value) {
            return@LaunchedEffect
        }
        delay(16L)
        controller.attach(
            sourceUrl = sourceUrl,
            sourceHeaders = playbackHeaders,
            playWhenReady = playWhenReady,
            initialPositionMs = initialPositionMs,
            decoderPriority = decoderPriority,
            nvidiaRtxSuperResolutionEnabled = nvidiaRtxSuperResolutionEnabled,
            nvidiaRtxHdrEnabled = nvidiaRtxHdrEnabled,
            onError = { message -> latestOnError.value(message) },
        )
        initialPositionRequestKey?.let { key ->
            latestOnInitialPositionHandled.value(key, initialPositionMs > 0L)
        }
        onControllerReady(readyController)
    }

    LaunchedEffect(controller, sourceAvailable, playWhenReady) {
        if (!sourceAvailable) return@LaunchedEffect
        if (playWhenReady) {
            controller.play()
        } else {
            controller.pause()
        }
    }

    LaunchedEffect(controller, resizeMode) {
        controller.setResizeMode(resizeMode)
    }

    LaunchedEffect(controller, controlsState) {
        controller.updateControls(controlsState)
        DesktopPlayerPictureInPicture.setWindowTitle(controlsState.pipWindowTitle)
    }

    LaunchedEffect(controller) {
        DesktopPlayerPictureInPicture.changes.drop(1).collect {
            controller.updateControls(latestControlsState.value)
        }
    }

    LaunchedEffect(controller) {
        AppPresenceState.current.collect { snapshot ->
            val player = snapshot as? PresenceSnapshot.Player
            controller.setNowPlayingMetadata(player?.title, player?.episodeLabel, player?.posterUrl)
        }
    }

    LaunchedEffect(controller) {
        desktopFullscreenChanges.drop(1).collect {
            controller.onDesktopFullscreenChanged()
        }
    }

    LaunchedEffect(controller) {
        while (true) {
            if (!controller.isReleasing) latestOnSnapshot.value(controller.snapshot())
            delay(250L)
        }
    }

    val pipChanges by DesktopPlayerPictureInPicture.changes.collectAsState()
    val isInPip = pipChanges >= 0 && DesktopPlayerPictureInPicture.isEnabled

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(
                if (!guidePreview) Modifier else Modifier.onGloballyPositioned { coordinates ->
                    // On macOS the native host is the whole window's content view: the preview
                    // takes the rectangle Compose laid this box out at, in window points.
                    val window = javax.swing.SwingUtilities.getWindowAncestor(host)
                    val scale = (window?.graphicsConfiguration ?: host.graphicsConfiguration)
                        ?.defaultTransform?.scaleX ?: return@onGloballyPositioned
                    val r = coordinates.boundsInWindow()
                    val viewport = listOf(r.left / scale, r.top / scale, r.width / scale, r.height / scale)
                    if (viewport[2] > 1.0 && viewport[3] > 1.0) {
                        viewport.forEachIndexed { i, v -> lastViewport[i] = v }
                        if (!guidePreviewHidden) controller.setGuideViewport(viewport[0], viewport[1], viewport[2], viewport[3])
                    }
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        // SwingPanel keeps the stable AWT host. PiP moves only the native player
        // surface to the independent window, so Compose never loses its host peer.
        CompositionLocalProvider(LocalDensity provides platformDensity) {
            SwingPanel(
                factory = { host },
                modifier = if (isInPip) {
                    Modifier
                        .align(Alignment.BottomEnd)
                        .requiredSize(1.dp)
                } else if (hostFirstPaintComplete.value) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier
                        .align(Alignment.BottomEnd)
                        .requiredSize(1.dp)
                },
                background = Color.Black,
            )
        }

        // Placeholder overlay shown when video has moved to PiP window
        if (isInPip) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(24.dp),
                ) {
                    Text(
                        text = playerControlsState.pipPlaceholderTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { DesktopPlayerPictureInPicture.clear() },
                    ) {
                        Text(playerControlsState.pipRestoreLabel)
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopStubPlayerSurface(
    modifier: Modifier,
    initialPositionRequestKey: String?,
    onInitialPositionHandled: (key: String, handled: Boolean) -> Unit,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
) {
    val controller = remember { DesktopStubPlayerController() }

    LaunchedEffect(controller) {
        onControllerReady(controller)
        onSnapshot(PlayerPlaybackSnapshot(isLoading = false))
    }

    LaunchedEffect(initialPositionRequestKey) {
        initialPositionRequestKey?.let { key -> onInitialPositionHandled(key, false) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Desktop in-app playback is not available yet.",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private class DesktopStubPlayerController : PlayerEngineController {
    override fun play() = Unit
    override fun pause() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun seekBy(offsetMs: Long) = Unit
    override fun retry() = Unit
    override fun setPlaybackSpeed(speed: Float) = Unit
    override fun getAudioTracks(): List<AudioTrack> = emptyList()
    override fun getSubtitleTracks(): List<SubtitleTrack> = emptyList()
    override fun applyAudioLanguagePreferences(languages: List<String>) = Unit
    override fun selectAudioTrack(index: Int) = Unit
    override fun selectSubtitleTrack(index: Int) = Unit
    override fun setSubtitleUri(url: String) = Unit
    override fun clearExternalSubtitle() = Unit
    override fun clearExternalSubtitleAndSelect(trackIndex: Int) = Unit
}
