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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.drop
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.luminance
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.format.formatLocalTime
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
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.livetv_guide_no_info
import nuvio.composeapp.generated.resources.livetv_no_results
import nuvio.composeapp.generated.resources.livetv_recordings
import nuvio.composeapp.generated.resources.livetv_tv_guide_display
import nuvio.composeapp.generated.resources.livetv_tv_guide_display_24h
import nuvio.composeapp.generated.resources.livetv_tv_guide_display_logos
import nuvio.composeapp.generated.resources.livetv_tv_guide_display_now_line
import nuvio.composeapp.generated.resources.livetv_tv_guide_display_numbers
import nuvio.composeapp.generated.resources.livetv_tv_guide_display_times
import nuvio.composeapp.generated.resources.livetv_tv_guide_earlier
import nuvio.composeapp.generated.resources.livetv_tv_guide_later
import nuvio.composeapp.generated.resources.livetv_tv_guide_preview
import nuvio.composeapp.generated.resources.livetv_tv_guide_preview_warning
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

// The Android TV guide (TiviMate style): a fixed two-hour window that fills the width and moves in
// half-hour steps, glass tiles with a faint white edge, and the theme's glow behind the info panel.
private const val SlotMs = LiveTvGuideHalfHourMs
private const val WindowMs = 4 * SlotMs
private const val HourMs = 2 * SlotMs
private val ChannelColumnWidth = 272.dp
private val RulerHeight = 34.dp
private val ScrollbarWidth = 9.dp
private val TileShape = RoundedCornerShape(10.dp)
private val GlassEdge = Color.White.copy(alpha = 0.10f)

private fun rowHeight(s: LiveTvSettings): Dp = if (s.showProgramTimes) 52.dp else 40.dp

/** The guide's dark, see-through glass: the theme background (near-black themes get a fixed dark grey). */
@Composable
internal fun guideGlass(alpha: Float = 0.66f): Color {
    val c = MaterialTheme.nuvio.colors.background
    return (if (c.luminance() < 0.003f) Color(0xFF0E0E12) else c).copy(alpha = alpha)
}

private data class GuideSelection(val channelKey: String, val start: Long? = null)

/** A show clipped to the visible window, or a gap with no listing. */
private data class GuideCell(val start: Long, val end: Long, val program: EpgProgram? = null)

private fun windowCells(programs: List<EpgProgram>, start: Long, end: Long): List<GuideCell> = buildList {
    var cursor = start
    programs.sortedBy { it.startMs }.forEach { program ->
        val left = maxOf(cursor, program.startMs)
        val right = minOf(end, program.stopMs)
        if (right <= left) return@forEach
        if (left - cursor > 60_000L) add(GuideCell(cursor, left))
        add(GuideCell(left, right, program))
        cursor = right
    }
    if (end - cursor > 60_000L) add(GuideCell(cursor, end))
}

@OptIn(ExperimentalComposeUiApi::class)
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
    onOpenRecordings: (() -> Unit)? = null,
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
    val rowHeight = rowHeight(settings)
    // The preview window (togglable in Guide display): one silent second player at most, and its
    // stream is released before the full player opens a channel.
    val previewOn by DesktopGuidePreviewSetting.enabled.collectAsState()
    val preview = remember { DesktopGuidePreviewState() }
    DisposableEffect(preview) { onDispose { preview.release {} } }
    LaunchedEffect(previewOn) { if (!previewOn) preview.release {} }
    // Pause when idle: stop the preview after two minutes with no mouse movement over the guide.
    val lastActivity = remember { longArrayOf(0L) }
    LaunchedEffect(preview.channel) {
        if (preview.channel == null) return@LaunchedEffect
        lastActivity[0] = System.currentTimeMillis()
        while (true) {
            delay(10_000)
            if (System.currentTimeMillis() - lastActivity[0] > 120_000) {
                preview.release {}
                preview.pausedIdle = true
                break
            }
        }
    }
    val playChannel: (LiveChannel) -> Unit = { channel -> preview.release { onPlay(channel) } }
    val channelClick: (LiveChannel) -> Unit = { channel ->
        if (previewOn && preview.channel?.key != channel.key) preview.start(channel) else playChannel(channel)
    }

    // The shared screen keeps the time position in hScroll (Jump to Now, loading the visible
    // listings), in its own 216 dp-per-hour units. This guide shows the half-hour page it falls in.
    val pxPerMs = with(LocalDensity.current) { LiveTvWideGuideHourWidth.toPx() } / HourMs
    val lastStart = maxOf(gridStart, gridEnd - WindowMs)
    fun slotOf(t: Long) = (t - (t - gridStart).mod(SlotMs)).coerceIn(gridStart, lastStart)
    // The window keeps its own position, opening on the current half hour. It follows hScroll
    // when that moves (wheel, the shared screen's jumps), and pages also move hScroll so the
    // shared screen loads the listings in view.
    var windowStart by remember(gridStart) { mutableStateOf(slotOf(now)) }
    LaunchedEffect(hScroll, gridStart, pxPerMs) {
        snapshotFlow { hScroll.value }.drop(1).collect { value ->
            windowStart = slotOf(gridStart + (value / pxPerMs).toLong())
        }
    }
    val windowEnd = windowStart + WindowMs
    val scope = rememberCoroutineScope()
    fun page(slots: Int) {
        windowStart = slotOf(windowStart + slots * SlotMs)
        // A pixel past the slot edge, so rounding never lands on the previous half hour.
        scope.launch { hScroll.scrollTo(((windowStart - gridStart) * pxPerMs).roundToInt() + 1) }
    }
    val wheel = Modifier.onDesktopHorizontalWheel { hScroll.dispatchRawDelta(it) }

    Box(modifier.fillMaxWidth().testTag("desktop-tv-guide")
        .onPointerEvent(PointerEventType.Move) { lastActivity[0] = System.currentTimeMillis() }) {
        GuideThemeGlow(Modifier.fillMaxWidth().height(230.dp))
        Column(Modifier.fillMaxSize()) {
            if (selectedChannel != null) {
                DesktopTvGuideDetails(selectedChannel, selectedProgram, now, clock, loadProgramDetails,
                    onPlay = { playChannel(selectedChannel) },
                    onChannelActions = onChannelActions?.let { { it(selectedChannel) } },
                    extraActions = { GuideHeaderActions(settings, previewOn, onOpenRecordings) { preview.covered = it } },
                    preview = if (previewOn) ({ DesktopGuidePreviewWindow(preview) }) else null)
            }
            // Gives hScroll its range; nothing is drawn here.
            Box(Modifier.width(1.dp).height(0.dp).horizontalScroll(hScroll, enabled = false)) {
                Box(Modifier.width(LiveTvWideGuideHourWidth * ((gridEnd - gridStart).toFloat() / HourMs)).height(0.dp))
            }
            Row(Modifier.fillMaxWidth().height(40.dp).padding(start = 4.dp, end = ScrollbarWidth),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { corner() }
                TextButton({ page(-1) }, enabled = windowStart > gridStart) {
                    Text("‹  " + stringResource(Res.string.livetv_tv_guide_earlier), fontSize = 13.sp)
                }
                TextButton({ page(1) }, enabled = windowStart < lastStart) {
                    Text(stringResource(Res.string.livetv_tv_guide_later) + "  ›", fontSize = 13.sp)
                }
            }
            GuideRuler(windowStart, now, clock, Modifier.fillMaxWidth().height(RulerHeight)
                .padding(end = ScrollbarWidth + 6.dp, bottom = 4.dp).then(wheel))
            Box(Modifier.fillMaxWidth().weight(1f)) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(end = ScrollbarWidth),
                    contentPadding = PaddingValues(bottom = 12.dp)) {
                    if (channels.isEmpty()) item {
                        Text(stringResource(Res.string.livetv_no_results), Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
                    }
                    items(channels, key = { it.key }) { channel ->
                        val rowPrograms = programs[channel.key].orEmpty()
                        val cells = remember(rowPrograms, windowStart) { windowCells(rowPrograms, windowStart, windowEnd) }
                        val channelSelected = channel.key == selectedChannel?.key
                        Row(Modifier.fillMaxWidth().height(rowHeight).padding(vertical = 2.dp)) {
                            GuideChannelCell(channel, settings, rowHeight, channel.key in favoriteKeys, channelSelected,
                                onBrowse = { selection = GuideSelection(channel.key) }, onPlay = { channelClick(channel) },
                                onActions = onChannelActions?.let { { it(channel) } })
                            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().then(wheel)) {
                                val areaWidth = maxWidth
                                fun xOf(t: Long): Dp = areaWidth * ((t - windowStart).toFloat() / WindowMs)
                                if (cells.isEmpty()) {
                                    GuideGapTile(Modifier.fillMaxSize().padding(horizontal = 3.dp), showLabel = true)
                                }
                                cells.forEach { cell ->
                                    val x = xOf(cell.start)
                                    val w = (xOf(cell.end) - x).coerceAtLeast(2.dp)
                                    val tile = Modifier.offset(x = x).width(w).fillMaxHeight().padding(horizontal = 3.dp)
                                    val program = cell.program
                                    if (program == null) {
                                        GuideGapTile(tile, showLabel = w > 90.dp)
                                    } else {
                                        val live = program.isLive(now)
                                        GuideProgramTile(
                                            program = program,
                                            live = live,
                                            past = program.stopMs <= now,
                                            selected = channelSelected && selectedProgram?.startMs == program.startMs,
                                            continuesLeft = program.startMs < windowStart,
                                            replayable = !live && LiveTvCatchup.isReplayable(channel, program, now),
                                            timeRange = if (settings.showProgramTimes && w > 64.dp)
                                                clock(program.startMs) + " – " + clock(program.stopMs) else null,
                                            progress = if (live) program.progress(now) else null,
                                            modifier = tile,
                                            onBrowse = { selection = GuideSelection(channel.key, program.startMs) },
                                            onOpen = { onProgram(channel, program) },
                                            onPlay = { playChannel(channel) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (settings.showNowLine && now in windowStart until windowEnd) {
                    val nowColor = tokens.colors.accent
                    Box(Modifier.matchParentSize().padding(start = ChannelColumnWidth, end = ScrollbarWidth)
                        .clipToBounds().drawWithContent {
                            drawContent()
                            val left = size.width * ((now - windowStart).toFloat() / WindowMs)
                            val x = if (layoutDirection == LayoutDirection.Ltr) left else size.width - left
                            drawLine(nowColor.copy(alpha = 0.8f), Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
                        })
                }
                NuvioDesktopVerticalScrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
    }
}

/** The TV guide's info-panel corner: ● Recordings and ⚙ Guide display (what the rows show). */
@Composable
private fun GuideHeaderActions(
    settings: LiveTvSettings,
    previewOn: Boolean,
    onOpenRecordings: (() -> Unit)?,
    onMenuOpen: (Boolean) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    onOpenRecordings?.let {
        IconButton(it, Modifier.testTag("desktop-tv-guide-recordings")) {
            Icon(Icons.Filled.FiberManualRecord, stringResource(Res.string.livetv_recordings), tint = Color(0xFFE53935))
        }
    }
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(open) { onMenuOpen(open) }
    Box {
        IconButton({ open = true }, Modifier.testTag("desktop-tv-guide-display")) {
            Icon(Icons.Filled.Tune, stringResource(Res.string.livetv_tv_guide_display), tint = tokens.colors.textSecondary)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            Text(stringResource(Res.string.livetv_tv_guide_display), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                fontSize = 13.sp, fontWeight = FontWeight.Bold, color = tokens.colors.textSecondary)
            DisplayToggle(Res.string.livetv_tv_guide_preview, previewOn) { v -> DesktopGuidePreviewSetting.set(v) }
            Text(stringResource(Res.string.livetv_tv_guide_preview_warning),
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp).width(260.dp),
                fontSize = 12.sp, color = tokens.colors.warning)
            DisplayToggle(Res.string.livetv_tv_guide_display_numbers, settings.phoneChannelNumbers) { v ->
                LiveTvPreferences.updateSettings { it.copy(phoneChannelNumbers = v) } }
            DisplayToggle(Res.string.livetv_tv_guide_display_logos, settings.phoneChannelLogos) { v ->
                LiveTvPreferences.updateSettings { it.copy(phoneChannelLogos = v) } }
            DisplayToggle(Res.string.livetv_tv_guide_display_times, settings.showProgramTimes) { v ->
                LiveTvPreferences.updateSettings { it.copy(showProgramTimes = v) } }
            DisplayToggle(Res.string.livetv_tv_guide_display_now_line, settings.showNowLine) { v ->
                LiveTvPreferences.updateSettings { it.copy(showNowLine = v) } }
            DisplayToggle(Res.string.livetv_tv_guide_display_24h, settings.use24HourClock) { v ->
                LiveTvPreferences.updateSettings { it.copy(use24HourClock = v) } }
        }
    }
}

@Composable
private fun DisplayToggle(label: org.jetbrains.compose.resources.StringResource, checked: Boolean, onChange: (Boolean) -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        onClick = { onChange(!checked) },
        trailingIcon = { Checkbox(checked, onCheckedChange = null) },
    )
}

/** Date over the channels, a label every half hour, and the current time at the right end. */
@Composable
private fun GuideRuler(windowStart: Long, now: Long, clock: (Long) -> String, modifier: Modifier) {
    val tokens = MaterialTheme.nuvio
    val day = remember(windowStart / HourMs) {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            .format(Instant.ofEpochMilli(windowStart).atZone(ZoneId.systemDefault()).toLocalDate())
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(ChannelColumnWidth).padding(start = 8.dp)) {
            Text(day, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = tokens.colors.textPrimary, maxLines = 1)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            val w = maxWidth
            for (i in 0 until (WindowMs / SlotMs).toInt()) {
                val t = windowStart + i * SlotMs
                Text(clock(t), Modifier.offset(x = w * (i.toFloat() / (WindowMs / SlotMs))).align(Alignment.CenterStart)
                    .padding(start = 6.dp), fontSize = 13.sp, color = tokens.colors.textSecondary, maxLines = 1)
            }
            Text(clock(now), Modifier.align(Alignment.CenterEnd).clip(RoundedCornerShape(8.dp))
                .background(guideGlass()).padding(horizontal = 10.dp, vertical = 3.dp),
                fontSize = 14.sp, fontWeight = FontWeight.Bold, color = tokens.colors.textPrimary, maxLines = 1)
        }
    }
}

@Composable
private fun GuideChannelCell(
    channel: LiveChannel,
    settings: LiveTvSettings,
    rowHeight: Dp,
    favorite: Boolean,
    selected: Boolean,
    onBrowse: () -> Unit,
    onPlay: () -> Unit,
    onActions: (() -> Unit)?,
) {
    val tokens = MaterialTheme.nuvio
    GuideTile(selected, onBrowse, onPlay, onActions, Modifier.width(ChannelColumnWidth).fillMaxHeight().padding(end = 6.dp)) { focused ->
        Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (settings.phoneChannelNumbers) {
                Text(channel.number.toString(), Modifier.width(40.dp), textAlign = TextAlign.End, fontSize = 14.sp,
                    fontWeight = FontWeight.Medium, color = tokens.colors.textSecondary, maxLines = 1)
                Spacer(Modifier.width(8.dp))
            }
            if (settings.phoneChannelLogos) {
                val logoHeight = (rowHeight - 14.dp).coerceAtLeast(18.dp)
                ChannelLogo(channel, width = logoHeight * 1.6f, height = logoHeight, inset = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(channel.name, Modifier.weight(1f), fontSize = 14.sp,
                fontWeight = if (focused || selected) FontWeight.SemiBold else FontWeight.Normal,
                color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (channel.catchup != null) {
                Icon(Icons.Filled.Replay, null, Modifier.padding(start = 4.dp).size(14.dp), tint = tokens.colors.textSecondary)
            }
            if (favorite) {
                Icon(Icons.Filled.Star, null, Modifier.padding(start = 4.dp).size(14.dp), tint = tokens.colors.warning)
            }
        }
    }
}

@Composable
private fun GuideProgramTile(
    program: EpgProgram,
    live: Boolean,
    past: Boolean,
    selected: Boolean,
    continuesLeft: Boolean,
    replayable: Boolean,
    timeRange: String?,
    progress: Float?,
    modifier: Modifier,
    onBrowse: () -> Unit,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val fallback = stringResource(Res.string.livetv_guide_no_info)
    GuideTile(selected, onBrowse, onOpen, onOpen, modifier.onPreviewKeyEvent {
        // Enter tunes the channel; a click (or Space) opens the shared details dialog, where
        // catch-up and title lookup live.
        if (it.key != Key.Enter && it.key != Key.NumPadEnter) return@onPreviewKeyEvent false
        if (it.type == KeyEventType.KeyUp) onPlay()
        true
    }) { focused ->
        val text = if (past && !focused && !selected) tokens.colors.textSecondary else tokens.colors.textPrimary
        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text((if (continuesLeft) "‹ " else "") + RecordingRules.shownTitle(program.title).ifBlank { fallback },
                    Modifier.weight(1f, fill = false), fontSize = 14.sp,
                    fontWeight = if (live || focused || selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (replayable) Icon(Icons.Filled.Replay, null, Modifier.padding(start = 6.dp).size(13.dp), tint = text.copy(alpha = 0.8f))
            }
            timeRange?.let {
                Text(it, fontSize = 12.sp, color = text.copy(alpha = if (focused) 0.6f else 0.42f), maxLines = 1)
            }
        }
        progress?.let { fraction ->
            Box(Modifier.align(Alignment.BottomStart).padding(start = 2.dp, end = 2.dp, bottom = 2.dp).fillMaxWidth().height(3.dp)
                .clip(RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))) {
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(tokens.colors.accent.copy(alpha = 0.9f)))
            }
        }
    }
}

@Composable
private fun GuideGapTile(modifier: Modifier, showLabel: Boolean) {
    val tokens = MaterialTheme.nuvio
    Box(modifier.clip(TileShape).background(guideGlass(0.36f)).border(1.dp, Color.White.copy(alpha = 0.07f), TileShape),
        contentAlignment = Alignment.CenterStart) {
        if (showLabel) Text(stringResource(Res.string.livetv_guide_no_info), Modifier.padding(horizontal = 12.dp),
            fontSize = 14.sp, fontStyle = FontStyle.Italic, color = tokens.colors.textMuted, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

/** A glass tile that follows the mouse like the TV's focus: hover or focus selects it, click activates. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun GuideTile(
    selected: Boolean,
    onBrowse: () -> Unit,
    onActivate: () -> Unit,
    onSecondary: (() -> Unit)?,
    modifier: Modifier,
    content: @Composable androidx.compose.foundation.layout.BoxScope.(focused: Boolean) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var hovered by remember { mutableStateOf(false) }
    val highlighted = focused || hovered || selected
    val fill = if (highlighted) tokens.colors.focusBackground else guideGlass()
    val edge = when {
        focused -> tokens.colors.focusRing
        highlighted -> tokens.colors.focusRing.copy(alpha = 0.7f)
        else -> GlassEdge
    }
    Box(modifier.clip(TileShape).background(fill).border(if (highlighted) 2.dp else 1.dp, edge, TileShape)
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
        content(focused)
    }
}

/** The TV guide's soft glow in the theme's accent behind the info panel, fading into the guide. */
@Composable
private fun GuideThemeGlow(modifier: Modifier) {
    val accent = MaterialTheme.nuvio.colors.accent
    val top = MaterialTheme.nuvio.colors.surfaceElevated
    Box(modifier.drawBehind {
        val w = size.width
        val h = size.height
        val strength = if (accent.luminance() > 0.8f) 0.08f else 0.16f
        drawRect(Brush.verticalGradient(listOf(top.copy(alpha = 0.85f), Color.Transparent), startY = 0f, endY = h))
        val right = Offset(w * 0.80f, h * 0.04f)
        scale(scaleX = 2.4f, scaleY = 1f, pivot = right) {
            drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = strength), Color.Transparent), right, h * 0.94f), h * 0.94f, right)
        }
        val left = Offset(w * 0.10f, 0f)
        scale(scaleX = 2.2f, scaleY = 1f, pivot = left) {
            drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = strength * 0.4f), Color.Transparent), left, h * 0.80f), h * 0.80f, left)
        }
    })
}
