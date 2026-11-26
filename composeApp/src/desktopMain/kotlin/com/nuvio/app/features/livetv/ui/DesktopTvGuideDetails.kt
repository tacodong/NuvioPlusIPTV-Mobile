package com.nuvio.app.features.livetv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.livetv.model.EpgProgram
import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.model.descriptionText
import com.nuvio.app.features.livetv.model.guideTitleWithSub
import com.nuvio.app.features.livetv.model.subTitle
import com.nuvio.app.features.livetv.record.RecordingRules
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.details_actions_menu_label
import nuvio.composeapp.generated.resources.livetv_tv_guide_catchup_tag
import nuvio.composeapp.generated.resources.livetv_tv_guide_live_tag
import nuvio.composeapp.generated.resources.livetv_tv_guide_minutes_left
import nuvio.composeapp.generated.resources.livetv_tv_guide_no_program_info
import nuvio.composeapp.generated.resources.livetv_tv_guide_time_range
import nuvio.composeapp.generated.resources.livetv_tv_guide_watch_channel
import org.jetbrains.compose.resources.stringResource

private val LiveRed = Color(0xFFE53935)

/**
 * The TV guide's info panel: a glass panel with the channel's logo, the channel line with LIVE and
 * CATCH-UP tags, the show's title large, its time, minutes left and category, a progress bar and
 * as much of the description as fits. The logo slot is where a preview can go later.
 */
@Composable
internal fun DesktopTvGuideDetails(
    channel: LiveChannel,
    program: EpgProgram?,
    now: Long,
    clock: (Long) -> String,
    loadProgramDetails: suspend (String, Long) -> EpgProgram?,
    onPlay: () -> Unit,
    onChannelActions: (() -> Unit)?,
    visual: @Composable (LiveChannel) -> Unit = { ChannelLogo(it, width = 118.dp, height = 70.dp, inset = 8.dp) },
    /** Recordings and Guide display, beside Watch (like the TV guide's info panel corner). */
    extraActions: @Composable () -> Unit = {},
) {
    val tokens = MaterialTheme.nuvio
    // The grid loads light rows; read the extras for just the settled selection, so sweeping the
    // mouse across the grid doesn't fetch every show it crosses.
    var enriched by remember(channel.key, program) { mutableStateOf<EpgProgram?>(null) }
    LaunchedEffect(channel.key, program, loadProgramDetails) {
        if (program != null && program.description == null) {
            delay(150)
            enriched = loadProgramDetails(channel.key, program.startMs)
        }
    }
    val details = enriched ?: program
    val airing = program?.isLive(now) == true
    val shape = RoundedCornerShape(20.dp)
    val glass = guideGlass()

    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp)
        .clip(shape)
        .background(Brush.verticalGradient(listOf(glass.copy(alpha = glass.alpha - 0.06f), glass)), shape)
        .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
        .testTag("desktop-tv-guide-details")) {
        val compact = maxWidth < 700.dp
        Row(Modifier.fillMaxWidth().height(if (compact) 136.dp else 164.dp).padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(Modifier.width(if (compact) 96.dp else 130.dp).fillMaxHeight(), contentAlignment = Alignment.Center) { visual(channel) }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(listOf(channel.number.toString(), channel.name).joinToString("  ") +
                        channel.sourceName.takeIf { it.isNotBlank() }?.let { "  ·  $it" }.orEmpty(),
                        Modifier.weight(1f, fill = false), fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        color = tokens.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (airing) {
                        Spacer(Modifier.width(10.dp))
                        Tag(stringResource(Res.string.livetv_tv_guide_live_tag), LiveRed, Color.White)
                    }
                    if (channel.catchup != null) {
                        Spacer(Modifier.width(6.dp))
                        Tag(stringResource(Res.string.livetv_tv_guide_catchup_tag), Color.Transparent, tokens.colors.textSecondary,
                            Modifier.border(1.dp, tokens.colors.borderDefault, RoundedCornerShape(5.dp)))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(details?.let { guideTitleWithSub(RecordingRules.shownTitle(it.title), it.subTitle) }
                    ?.takeIf { it.isNotBlank() } ?: channel.name,
                    Modifier.testTag("desktop-tv-guide-title"),
                    fontSize = if (compact) 22.sp else 28.sp, fontWeight = FontWeight.ExtraBold,
                    color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                if (program != null) {
                    val minutesLeft = ((program.stopMs - now + 59_999) / 60_000).toInt()
                    val meta = listOfNotNull(
                        stringResource(Res.string.livetv_tv_guide_time_range, clock(program.startMs), clock(program.stopMs)),
                        if (airing) stringResource(Res.string.livetv_tv_guide_minutes_left, minutesLeft) else null,
                        details?.episode,
                        details?.category,
                    ).joinToString("  ·  ")
                    Text(meta, fontSize = 14.sp, color = tokens.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (airing) {
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.width(260.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(tokens.colors.borderDefault)) {
                            Box(Modifier.fillMaxWidth(program.progress(now)).fillMaxHeight().background(tokens.colors.accent))
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                // As many description lines as fit in the space left.
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val lineHeight = with(LocalDensity.current) { 14.sp.toDp() } * 1.45f
                    val lines = (maxHeight / lineHeight).toInt().coerceIn(1, 6)
                    Text(details?.descriptionText?.takeIf { it.isNotBlank() }
                        ?: if (program == null) stringResource(Res.string.livetv_tv_guide_no_program_info) else "",
                        fontSize = 14.sp, color = tokens.colors.textSecondary, maxLines = lines, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(verticalAlignment = Alignment.Top) {
                extraActions()
                val watch = stringResource(Res.string.livetv_tv_guide_watch_channel)
                if (compact) {
                    IconButton(onPlay, Modifier.testTag("desktop-tv-guide-watch")) { Icon(Icons.Rounded.PlayArrow, watch) }
                } else {
                    FilledTonalButton(onPlay, Modifier.testTag("desktop-tv-guide-watch")) {
                        Icon(Icons.Rounded.PlayArrow, null, Modifier.padding(end = 4.dp).size(20.dp))
                        Text(watch, maxLines = 1)
                    }
                }
                onChannelActions?.let {
                    IconButton(it) { Icon(Icons.Filled.MoreVert, stringResource(Res.string.details_actions_menu_label), tint = tokens.colors.textSecondary) }
                }
            }
        }
    }
}

@Composable
private fun Tag(text: String, background: Color, color: Color, modifier: Modifier = Modifier) {
    Text(text, modifier.clip(RoundedCornerShape(5.dp)).background(background).padding(horizontal = 6.dp, vertical = 1.dp),
        fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
}
