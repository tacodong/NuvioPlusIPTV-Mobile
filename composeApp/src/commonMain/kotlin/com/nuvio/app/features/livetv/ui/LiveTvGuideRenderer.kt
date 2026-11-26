package com.nuvio.app.features.livetv.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.livetv.model.EpgProgram
import com.nuvio.app.features.livetv.model.LiveChannel

// Both the renderer and the shared jump-to-now/archive viewport calculations use this geometry.
internal val LiveTvWideGuideChannelWidth = 178.dp
internal val LiveTvWideGuideHourWidth = 216.dp
internal const val LiveTvGuideHalfHourMs = 30 * 60_000L

internal fun interface LiveTvGuideRenderer {
    @Composable
    fun Render(
        channels: List<LiveChannel>,
        programs: Map<String, List<EpgProgram>>,
        favoriteKeys: Set<String>,
        gridStart: Long,
        gridEnd: Long,
        now: Long,
        hScroll: ScrollState,
        listState: LazyListState,
        onPlay: (LiveChannel) -> Unit,
        onProgram: (LiveChannel, EpgProgram) -> Unit,
        onChannelActions: ((LiveChannel) -> Unit)?,
        modifier: Modifier,
        corner: @Composable () -> Unit,
    )
}

/** Null preserves the existing shared renderer and polling cadence. */
internal expect val platformLiveTvGuideRenderer: LiveTvGuideRenderer?
