package com.nuvio.app.features.livetv.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.StarBorder
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioDesktopVerticalScrollbar
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioProgressBar
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.onDesktopSecondaryClick
import com.nuvio.app.features.livetv.data.NowNext
import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.model.descriptionText
import com.nuvio.app.features.livetv.record.RecordingRules
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_play
import nuvio.composeapp.generated.resources.livetv_guide
import nuvio.composeapp.generated.resources.livetv_guide_no_info
import nuvio.composeapp.generated.resources.livetv_guide_now
import nuvio.composeapp.generated.resources.livetv_group_channels
import nuvio.composeapp.generated.resources.livetv_group_one_channel
import nuvio.composeapp.generated.resources.livetv_no_results
import nuvio.composeapp.generated.resources.livetv_search_hint
import nuvio.composeapp.generated.resources.livetv_update_now
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun WideLiveTvBrowser(
    sections: List<LiveGroupSection>,
    selected: LiveGroupSection?,
    searchResults: List<LiveChannel>?,
    favoriteKeys: Set<String>,
    nowNext: Map<String, NowNext>,
    now: Long,
    listState: LazyListState,
    onGroup: (LiveGroupSection) -> Unit,
    onGroupActions: (LiveGroupSection) -> Unit,
    onPickFavorites: (LiveGroupSection) -> Unit,
    onPlay: (LiveChannel) -> Unit,
    onChannelActions: (LiveChannel) -> Unit,
    onOpenGuide: () -> Unit,
    onRefresh: (() -> Unit)? = null,
) {
    val tokens = MaterialTheme.nuvio
    val channels = searchResults ?: selected?.channels.orEmpty()
    var focusedChannel by remember(selected?.group?.id, searchResults != null) { mutableStateOf<String?>(null) }
    val current = channels.firstOrNull { it.key == focusedChannel } ?: channels.firstOrNull()
    var lastGroup by remember { mutableStateOf(selected?.group?.id) }
    LaunchedEffect(selected?.group?.id, searchResults != null) {
        if (lastGroup != selected?.group?.id || searchResults != null) listState.scrollToItem(0)
        lastGroup = selected?.group?.id
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val hasDetails = maxWidth >= 980.dp
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (sections.isNotEmpty()) {
                val groupState = rememberLazyListState()
                Box(Modifier.width(if (hasDetails) 218.dp else 182.dp).fillMaxHeight().clip(tokens.shapes.card).background(tokens.colors.surface)) {
                    LazyColumn(
                        state = groupState,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(sections, key = { it.group.id }) { section ->
                            WideGroupRow(
                                section,
                                selected = searchResults == null && section.group.id == selected?.group?.id,
                                onClick = { onGroup(section) },
                                onActions = if (section.group.special) null else ({ onGroupActions(section) }),
                            )
                        }
                    }
                    NuvioDesktopVerticalScrollbar(groupState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val title = if (searchResults != null) stringResource(Res.string.livetv_search_hint) else selected?.group?.title.orEmpty()
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (selected != null && !selected.group.special && selected.channels.isNotEmpty() && searchResults == null) {
                        IconButton(onClick = { onPickFavorites(selected) }) {
                            Icon(Icons.Filled.StarBorder, contentDescription = "Pick favorites", tint = tokens.colors.textMuted)
                        }
                    }
                }
                if (channels.isEmpty()) {
                    Text(stringResource(Res.string.livetv_no_results), style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
                    if (onRefresh != null) NuvioPrimaryButton(stringResource(Res.string.livetv_update_now), Modifier.widthIn(max = 280.dp), onClick = onRefresh)
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        items(channels, key = { it.key }) { channel ->
                            ChannelRow(
                                channel, nowNext[channel.key], now, channel.key in favoriteKeys,
                                onClick = { onPlay(channel) },
                                onLongClick = { onChannelActions(channel) },
                                modifier = Modifier.onFocusChanged { if (it.isFocused) focusedChannel = channel.key },
                                wide = true,
                                onHover = { focusedChannel = channel.key },
                            )
                        }
                    }
                    NuvioDesktopVerticalScrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
            if (hasDetails && current != null) {
                WideChannelDetails(current, nowNext[current.key], now, { onPlay(current) }, onOpenGuide, Modifier.width(270.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WideGroupRow(section: LiveGroupSection, selected: Boolean, onClick: () -> Unit, onActions: (() -> Unit)?) {
    val tokens = MaterialTheme.nuvio
    val interaction = remember { MutableInteractionSource() }
    val highlight = interaction.isHoveredOrFocused()
    Row(
        modifier = Modifier.fillMaxWidth().clip(tokens.shapes.compactCard)
            .background(when {
                selected -> tokens.colors.accent.copy(alpha = 0.16f)
                highlight -> tokens.colors.surfaceCard
                else -> androidx.compose.ui.graphics.Color.Transparent
            })
            .then(if (onActions != null) Modifier.onDesktopSecondaryClick(onActions) else Modifier)
            .combinedClickable(interactionSource = interaction, onClick = onClick, onLongClick = onActions)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        GroupIconBox(section.group, size = 29.dp)
        Column(Modifier.weight(1f)) {
            Text(section.group.title, color = if (selected) tokens.colors.accent else tokens.colors.textPrimary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!section.group.locked) {
                val count = section.channels.size
                Text(if (count == 1) stringResource(Res.string.livetv_group_one_channel) else stringResource(Res.string.livetv_group_channels, count), color = tokens.colors.textMuted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun WideChannelDetails(channel: LiveChannel, schedule: NowNext?, now: Long, onPlay: () -> Unit, onOpenGuide: () -> Unit, modifier: Modifier) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier.clip(tokens.shapes.card).background(tokens.colors.surface).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ChannelLogo(channel, width = 86.dp, height = 52.dp, inset = 4.dp)
        Text(channel.name, style = MaterialTheme.typography.titleLarge, color = tokens.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(channel.sourceName, style = MaterialTheme.typography.labelMedium, color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val program = schedule?.now
        if (program == null) {
            Text(stringResource(Res.string.livetv_guide_no_info), style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
        } else {
            Text(stringResource(Res.string.livetv_guide_now) + " · " + formatClock(program.startMs) + " – " + formatClock(program.stopMs), style = MaterialTheme.typography.labelMedium, color = tokens.colors.accent)
            Text(RecordingRules.shownTitle(program.title), style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            NuvioProgressBar(progress = program.progress(now), height = 3.dp, fillColor = tokens.colors.accent)
            program.descriptionText?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textSecondary, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
        schedule?.next?.let {
            Text(formatClock(it.startMs) + " · " + RecordingRules.shownTitle(it.title), style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        NuvioPrimaryButton(stringResource(Res.string.action_play), onClick = onPlay)
        androidx.compose.material3.TextButton(onClick = onOpenGuide) { Text(stringResource(Res.string.livetv_guide)) }
    }
}
