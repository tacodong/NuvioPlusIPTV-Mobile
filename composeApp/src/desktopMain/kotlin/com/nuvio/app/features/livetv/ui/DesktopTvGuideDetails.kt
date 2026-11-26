package com.nuvio.app.features.livetv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioProgressBar
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
import nuvio.composeapp.generated.resources.livetv_guide_no_info
import nuvio.composeapp.generated.resources.livetv_tv_guide_on_now
import nuvio.composeapp.generated.resources.livetv_tv_guide_time_range
import nuvio.composeapp.generated.resources.livetv_tv_guide_watch_channel
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DesktopTvGuideDetails(
    channel: LiveChannel,
    program: EpgProgram?,
    now: Long,
    clock: (Long) -> String,
    loadProgramDetails: suspend (String, Long) -> EpgProgram?,
    onPlay: () -> Unit,
    onChannelActions: (() -> Unit)?,
    visual: @Composable (LiveChannel) -> Unit = { ChannelLogo(it, width = 108.dp, height = 64.dp, inset = 8.dp) },
) {
    val tokens = MaterialTheme.nuvio
    // The shared grid loads light EPG rows. Read extras for just the settled selection;
    // sweeping the mouse across the grid should not fetch every crossed programme.
    var enriched by remember(channel.key, program) { mutableStateOf<EpgProgram?>(null) }
    LaunchedEffect(channel.key, program, loadProgramDetails) {
        if (program != null && program.description == null) {
            delay(150)
            enriched = loadProgramDetails(channel.key, program.startMs)
        }
    }
    val details = enriched ?: program
    val noInfo = stringResource(Res.string.livetv_guide_no_info)
    val airing = program?.isLive(now) == true

    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 6.dp)
        .clip(tokens.shapes.compactCard).background(tokens.colors.surfaceCard)
        .border(1.dp, tokens.colors.borderSubtle, tokens.shapes.compactCard)
        .testTag("desktop-tv-guide-details")) {
        val compact = maxWidth < 650.dp
        Row(Modifier.fillMaxWidth().height(if (compact) 120.dp else 132.dp).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 16.dp)) {
            // A static logo today; a later preview renderer can occupy this slot without
            // changing the info hierarchy or the timeline below it.
            Box(Modifier.width(if (compact) 76.dp else 112.dp), contentAlignment = Alignment.Center) { visual(channel) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(listOf(channel.name, channel.sourceName).filter(String::isNotBlank).joinToString("  ·  "),
                    style = MaterialTheme.typography.labelMedium, color = tokens.colors.textSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(details?.let { guideTitleWithSub(RecordingRules.shownTitle(it.title), it.subTitle) }
                    ?.takeIf { it.isNotBlank() } ?: noInfo,
                    Modifier.testTag("desktop-tv-guide-title"),
                    style = MaterialTheme.typography.titleLarge, color = tokens.colors.textPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (program != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (airing) Text(stringResource(Res.string.livetv_tv_guide_on_now),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold, color = tokens.colors.warning, maxLines = 1)
                        Text(stringResource(Res.string.livetv_tv_guide_time_range, clock(program.startMs), clock(program.stopMs)),
                            style = MaterialTheme.typography.labelMedium, color = tokens.colors.textSecondary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    details?.descriptionText?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textSecondary,
                            maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (airing) NuvioProgressBar(program.progress(now), Modifier.fillMaxWidth(.45f),
                        height = 2.dp, fillColor = tokens.colors.warning)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val watch = stringResource(Res.string.livetv_tv_guide_watch_channel)
                if (compact) {
                    IconButton(onPlay, Modifier.testTag("desktop-tv-guide-watch")) {
                        Icon(Icons.Rounded.PlayArrow, watch)
                    }
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
