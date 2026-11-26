package com.nuvio.app.features.livetv.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.format.formatLocalTime
import com.nuvio.app.core.ui.NuvioDesktopHorizontalScrollbar
import com.nuvio.app.core.ui.NuvioDesktopVerticalScrollbar
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.onDesktopHorizontalWheel
import com.nuvio.app.core.ui.onDesktopSecondaryClick
import com.nuvio.app.features.livetv.data.LiveTvPreferences
import com.nuvio.app.features.livetv.data.LiveTvRepository
import com.nuvio.app.features.livetv.model.EpgProgram
import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.model.LiveTvSettings
import com.nuvio.app.features.livetv.player.LiveTvCatchup
import com.nuvio.app.features.livetv.record.RecordingRules
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.livetv_guide_no_info
import nuvio.composeapp.generated.resources.livetv_no_results
import nuvio.composeapp.generated.resources.livetv_player_channels
import org.jetbrains.compose.resources.stringResource

private val RowHeight = 58.dp
private val RulerHeight = 34.dp
private val ScrollbarWidth = 9.dp
private val SlotWidth = LiveTvWideGuideHourWidth / 2
private const val SlotMs = LiveTvGuideHalfHourMs

private data class GuideSelection(val channelKey: String, val start: Long? = null)
private data class GuideCell(val start: Long, val end: Long, val program: EpgProgram? = null)

// Clip overlapping listings in chronological order, as the old TV grid did, and give
// unknown portions of the timeline real width rather than letting later shows slide left.
private fun guideCells(programs: List<EpgProgram>, start: Long, end: Long): List<GuideCell> = buildList {
    var cursor = start
    programs.sortedBy { it.startMs }.forEach { program ->
        val left = maxOf(cursor, program.startMs)
        val right = minOf(end, program.stopMs)
        if (right <= left) return@forEach
        if (left > cursor) add(GuideCell(cursor, left))
        add(GuideCell(left, right, program))
        cursor = right
    }
    if (cursor < end) add(GuideCell(cursor, end))
}

private fun timeWidth(ms: Long) = SlotWidth * (ms.toDouble() / SlotMs).toFloat()

@Composable
internal fun DesktopTvGuide(
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
    modifier: Modifier = Modifier,
    corner: @Composable () -> Unit = {},
    loadProgramDetails: suspend (String, Long) -> EpgProgram? = LiveTvRepository::programDetails,
) {
    val tokens = MaterialTheme.nuvio
    val settings by LiveTvPreferences.settings.collectAsStateWithLifecycle()
    var selection by remember { mutableStateOf<GuideSelection?>(null) }
    val selectedChannel = channels.firstOrNull { it.key == selection?.channelKey } ?: channels.firstOrNull()
    val requestedStart = selection?.start?.takeIf { selection?.channelKey == selectedChannel?.key }
    val schedule = programs[selectedChannel?.key].orEmpty()
    val selectedProgram = schedule.firstOrNull { it.startMs == requestedStart }
        ?: schedule.firstOrNull { it.isLive(now) }
        ?: schedule.firstOrNull { it.startMs >= now }
    val clock = if (settings.use24HourClock) ::clock24 else ::formatLocalTime
    val slotCount = ((gridEnd - gridStart) / SlotMs).toInt().coerceAtLeast(0)

    Column(modifier.fillMaxWidth().testTag("desktop-tv-guide")) {
        if (selectedChannel != null) {
            DesktopTvGuideDetails(selectedChannel, selectedProgram, now, clock, loadProgramDetails,
                onPlay = { onPlay(selectedChannel) }, onChannelActions = onChannelActions?.let { { it(selectedChannel) } })
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
            val viewportWidth = (maxWidth - LiveTvWideGuideChannelWidth - ScrollbarWidth).coerceAtLeast(0.dp)
            val slotPx = with(LocalDensity.current) { SlotWidth.toPx() }
            val visibleSlots = (viewportWidth / SlotWidth).toInt() + 2
            // Keep an extra hour on each side for keyboard focus and scroll. Only recompose
            // the horizon when crossing a slot boundary; exact pixels are handled by ScrollState.
            val firstSlot by remember(hScroll, slotPx, slotCount) {
                derivedStateOf { (hScroll.value / slotPx).toInt().coerceIn(0, slotCount) }
            }
            val leadingSlot = (firstSlot - 2).coerceAtLeast(0)
            val trailingSlot = (firstSlot + visibleSlots + 2).coerceAtMost(slotCount)
            val leadingTime = gridStart + leadingSlot * SlotMs
            val trailingTime = gridStart + trailingSlot * SlotMs
            val currentDay = remember(gridStart, firstSlot) {
                Instant.ofEpochMilli(gridStart + firstSlot * SlotMs).atZone(ZoneId.systemDefault()).toLocalDate()
            }
            val dateLabel = remember(currentDay) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(currentDay) }
            val timelineWidth = timeWidth(gridEnd - gridStart)
            val wheel = Modifier.onDesktopHorizontalWheel { hScroll.dispatchRawDelta(it) }

            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(dateLabel, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                        color = tokens.colors.textSecondary)
                    Box(Modifier.width(LiveTvWideGuideChannelWidth - 16.dp)) { corner() }
                }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    Column(Modifier.fillMaxSize().padding(end = ScrollbarWidth)) {
                        Row(Modifier.fillMaxWidth().height(RulerHeight).background(tokens.colors.surfaceCard),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(LiveTvWideGuideChannelWidth).padding(horizontal = 8.dp),
                                contentAlignment = Alignment.CenterStart) {
                                Text(stringResource(Res.string.livetv_player_channels),
                                    style = MaterialTheme.typography.labelMedium, color = tokens.colors.textPrimary)
                            }
                            Box(Modifier.weight(1f).fillMaxHeight().then(wheel).horizontalScroll(hScroll, enabled = false)) {
                                Box(Modifier.width(timelineWidth).fillMaxHeight()) {
                                    for (slot in leadingSlot until trailingSlot) {
                                        val time = gridStart + slot * SlotMs
                                        val current = now in time until time + SlotMs
                                        Box(Modifier.offset(x = SlotWidth * slot).width(SlotWidth).fillMaxHeight()
                                            .padding(start = 8.dp), contentAlignment = Alignment.CenterStart) {
                                            Text(clock(time), style = MaterialTheme.typography.labelMedium,
                                                color = if (current) tokens.colors.textPrimary else tokens.colors.textSecondary,
                                                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                        if (channels.isEmpty()) {
                            Text(stringResource(Res.string.livetv_no_results), Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
                        }
                        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp), contentPadding = PaddingValues(top = 3.dp, bottom = 8.dp)) {
                            items(channels, key = { it.key }) { channel ->
                                val rowPrograms = programs[channel.key].orEmpty()
                                val cells = remember(rowPrograms, gridStart, gridEnd) { guideCells(rowPrograms, gridStart, gridEnd) }
                                val channelSelected = channel.key == selectedChannel?.key
                                Row(Modifier.fillMaxWidth().height(RowHeight)) {
                                    DesktopGuideChannel(channel, settings, channel.key in favoriteKeys, channelSelected,
                                        onBrowse = { selection = GuideSelection(channel.key) }, onPlay = { onPlay(channel) },
                                        onActions = onChannelActions?.let { { it(channel) } })
                                    Box(Modifier.weight(1f).fillMaxHeight().then(wheel).horizontalScroll(hScroll)) {
                                        Box(Modifier.width(timelineWidth).fillMaxHeight()) {
                                            cells.forEach { cell ->
                                                if (cell.end <= leadingTime || cell.start >= trailingTime) return@forEach
                                                val program = cell.program
                                                val width = timeWidth(cell.end - cell.start)
                                                val offset = timeWidth(cell.start - gridStart)
                                                val tile = Modifier.offset(x = offset).width(width).fillMaxHeight()
                                                    .padding(end = minOf(3.dp, width / 4))
                                                if (program == null) {
                                                    DesktopGuideGap(tile, showLabel = cells.size == 1, hScroll, offsetPx = 0)
                                                } else {
                                                    val logicalStartPx = with(LocalDensity.current) { offset.roundToPx() }
                                                    val browse = { selection = GuideSelection(channel.key, program.startMs) }
                                                    DesktopGuideProgram(program, program.isLive(now),
                                                        channelSelected && selectedProgram?.startMs == program.startMs,
                                                        replayable = !program.isLive(now) && LiveTvCatchup.isReplayable(channel, program, now),
                                                        tile, hScroll, logicalStartPx, singleLine = width < 100.dp, browse,
                                                        onOpen = { onProgram(channel, program) },
                                                        onPlay = { onPlay(channel) })
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (settings.showNowLine && now in gridStart until gridEnd) {
                        val nowColor = tokens.colors.warning
                        // One continuous line across the ruler and visible rows. Scroll position is
                        // read in drawing, not composition, and the shared clock ticks once a minute.
                        Box(Modifier.matchParentSize().padding(start = LiveTvWideGuideChannelWidth, end = ScrollbarWidth)
                            .clipToBounds().drawWithContent {
                                val left = timeWidth(now - gridStart).toPx() - hScroll.value
                                val x = if (layoutDirection == LayoutDirection.Ltr) left else size.width - left
                                if (x in 0f..size.width) drawLine(nowColor, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
                            })
                    }
                    NuvioDesktopVerticalScrollbar(listState, Modifier.align(Alignment.CenterEnd)
                        .padding(top = RulerHeight).fillMaxHeight())
                }
                NuvioDesktopHorizontalScrollbar(hScroll, Modifier.fillMaxWidth()
                    .padding(start = LiveTvWideGuideChannelWidth, end = ScrollbarWidth).height(12.dp))
            }
        }
    }
}

@Composable
private fun DesktopGuideChannel(
    channel: LiveChannel,
    settings: LiveTvSettings,
    favorite: Boolean,
    selected: Boolean,
    onBrowse: () -> Unit,
    onPlay: () -> Unit,
    onActions: (() -> Unit)?,
) {
    val tokens = MaterialTheme.nuvio
    Box(Modifier.width(LiveTvWideGuideChannelWidth).fillMaxHeight().padding(start = 4.dp, end = 4.dp)) {
        DesktopGuideTile(selected, false, onBrowse, onPlay, onActions, Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize().padding(start = 8.dp, end = if (favorite) 22.dp else 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (settings.phoneChannelLogos) ChannelLogo(channel, width = 38.dp, height = 30.dp, inset = 2.dp)
                Column(Modifier.weight(1f)) {
                    Text(channel.name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                        color = tokens.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (settings.phoneChannelNumbers) Text(channel.number.toString(), style = MaterialTheme.typography.labelSmall,
                        color = tokens.colors.textSecondary, maxLines = 1)
                }
            }
        }
        if (favorite) FavoriteBadge(Modifier.align(Alignment.TopEnd).padding(3.dp))
    }
}

@Composable
private fun DesktopGuideProgram(
    program: EpgProgram,
    live: Boolean,
    selected: Boolean,
    replayable: Boolean,
    modifier: Modifier,
    hScroll: ScrollState,
    offsetPx: Int,
    singleLine: Boolean,
    onBrowse: () -> Unit,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val fallback = stringResource(Res.string.livetv_guide_no_info)
    DesktopGuideTile(selected, live, onBrowse, onOpen, onOpen, modifier.onPreviewKeyEvent {
        // Match the existing desktop guide: Enter tunes the channel, while a click (or Space)
        // opens the shared details dialog, where catch-up and title lookup live.
        if (it.key != Key.Enter && it.key != Key.NumPadEnter) return@onPreviewKeyEvent false
        if (it.type == KeyEventType.KeyUp) onPlay()
        true
    }) {
        Row(Modifier.browseLabelOffset(hScroll, offsetPx).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (replayable) Icon(Icons.Filled.Replay, null, Modifier.size(12.dp), tint = tokens.colors.textSecondary)
            Text(RecordingRules.shownTitle(program.title).ifBlank { fallback },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (selected || live) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected || live) tokens.colors.textPrimary else tokens.colors.textSecondary,
                maxLines = if (singleLine) 1 else 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DesktopGuideGap(modifier: Modifier, showLabel: Boolean, hScroll: ScrollState, offsetPx: Int) {
    val tokens = MaterialTheme.nuvio
    Box(modifier.clip(tokens.shapes.compactCard).background(tokens.colors.surfaceCard.copy(alpha = .45f)),
        contentAlignment = Alignment.CenterStart) {
        if (showLabel) Text(stringResource(Res.string.livetv_guide_no_info),
            Modifier.browseLabelOffset(hScroll, offsetPx).padding(horizontal = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// A programme that starts before the visible ruler (including on Jump to Now) retains its title
// beside the pinned channel column until its tile scrolls completely out of view.
private fun Modifier.browseLabelOffset(hScroll: ScrollState, offsetPx: Int): Modifier = offset {
    IntOffset((hScroll.value - offsetPx).coerceAtLeast(0), 0)
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DesktopGuideTile(
    selected: Boolean,
    live: Boolean,
    onBrowse: () -> Unit,
    onActivate: () -> Unit,
    onSecondary: (() -> Unit)?,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var hovered by remember { mutableStateOf(false) }
    val border = when {
        focused -> tokens.colors.accentStrong
        hovered || selected -> tokens.colors.accent
        live -> tokens.colors.warning.copy(alpha = .55f)
        else -> tokens.colors.borderSubtle
    }
    val surface = when {
        focused || hovered || selected -> tokens.colors.accent.copy(alpha = .19f).compositeOver(tokens.colors.surfaceCard)
        live -> tokens.colors.warning.copy(alpha = .08f).compositeOver(tokens.colors.surfaceCard)
        else -> tokens.colors.surfaceCard
    }
    Box(modifier.clip(tokens.shapes.compactCard)
        .background(surface).border(if (focused) 2.dp else 1.dp, border, tokens.shapes.compactCard)
        .onPointerEvent(PointerEventType.Enter) { hovered = true; onBrowse() }
        .onPointerEvent(PointerEventType.Exit) { hovered = false }
        .focusRequester(focusRequester)
        .onFocusChanged { if (it.isFocused && !focused) onBrowse(); focused = it.isFocused }
        .then(if (onSecondary == null) Modifier else Modifier.onDesktopSecondaryClick(onSecondary))
        .semantics { this.selected = selected }
        .clickable(role = Role.Button) {
            focusRequester.requestFocus()
            onBrowse()
            onActivate()
        }, contentAlignment = Alignment.CenterStart) {
        content()
    }
}
