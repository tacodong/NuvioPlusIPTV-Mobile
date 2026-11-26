package com.nuvio.app.features.livetv.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nuvio.app.features.livetv.model.EpgProgram
import com.nuvio.app.features.livetv.model.LiveChannel

internal actual val platformLiveTvGuideRenderer: LiveTvGuideRenderer? =
    if (System.getProperty("os.name", "").startsWith("Mac", ignoreCase = true)) MacLiveTvGuideRenderer else null

private object MacLiveTvGuideRenderer : LiveTvGuideRenderer {
    @Composable
    override fun Render(
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
        onOpenRecordings: (() -> Unit)?,
    ) = DesktopTvGuide(channels, programs, favoriteKeys, gridStart, gridEnd, now, hScroll, listState,
        onPlay, onProgram, onChannelActions, modifier, corner, onOpenRecordings = onOpenRecordings)
}
