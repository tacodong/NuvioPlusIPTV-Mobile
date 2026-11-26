package com.nuvio.app.features.livetv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import com.nuvio.app.core.ui.Menu
import com.nuvio.app.core.ui.MenuItem
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.livetv.data.LiveTvPreferences
import com.nuvio.app.features.livetv.data.LiveTvRepository
import com.nuvio.app.features.livetv.model.PlaylistSource
import com.nuvio.app.features.livetv.sync.LiveTvDriveHttp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nuvio.composeapp.generated.resources.livetv_ui_all
import nuvio.composeapp.generated.resources.livetv_ui_all_playlists
import nuvio.composeapp.generated.resources.livetv_ui_playlist_all_hidden
import nuvio.composeapp.generated.resources.livetv_ui_playlist_profile_hidden
import nuvio.composeapp.generated.resources.livetv_ui_tap_to_manage
import nuvio.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.getString

/**
 * Which playlist the group view, the guide and On Demand show ("All playlists" = null). One choice
 * for all three, remembered on this phone only (it is not part of the synced setup).
 */
internal object PlaylistFilter {
    private const val KEY = "playlist_filter"

    private val _selected = MutableStateFlow(runCatching { LiveTvDriveHttp.getString(KEY) }.getOrNull()?.takeIf { it.isNotBlank() })
    val selected: StateFlow<String?> = _selected.asStateFlow()

    fun select(id: String?) {
        _selected.value = id
        runCatching { LiveTvDriveHttp.putString(KEY, id) }
    }

    /** The saved choice if that playlist is one of [ids], otherwise null (all playlists). */
    fun effective(saved: String?, ids: Collection<String>): String? = saved?.takeIf { it in ids }
}

internal data class PlaylistChoice(val id: String, val name: String, val detail: String)

/** A playlist whose channels loaded but that shows nothing here: hidden on this profile, or every group hidden. */
internal data class HiddenPlaylist(val id: String, val name: String, val profileHidden: Boolean)

/**
 * Playlists that are on and have channels, but none of whose groups are in [shownIds]: listed
 * (dimmed) in the playlist menu so they don't seem to have gone missing.
 */
@Composable
internal fun rememberHiddenPlaylists(playlists: List<PlaylistSource>, shownIds: Set<String>): List<HiddenPlaylist> {
    val channels by LiveTvRepository.displayChannels.collectAsStateWithLifecycle()
    val user by LiveTvPreferences.userState.collectAsStateWithLifecycle()
    val withChannels = remember(channels) { channels.mapTo(HashSet()) { it.sourceId } }
    return remember(playlists, shownIds, withChannels, user.hiddenPlaylists) {
        playlists.filter { it.liveEnabled && it.id in withChannels && it.id !in shownIds }
            .map { HiddenPlaylist(it.id, it.name, profileHidden = it.id in user.hiddenPlaylists) }
    }
}

/**
 * The playlist dropdown. Shows nothing with fewer than two playlists, since there is nothing to
 * pick from. [hidden] playlists are listed dimmed under the others, saying why they show nothing;
 * tapping one calls [onOpenHidden] (to manage groups or settings) when given.
 */
@Composable
internal fun PlaylistDropdown(
    choices: List<PlaylistChoice>,
    selectedId: String?,
    allDetail: String,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    /** Just the playlist name and an arrow, for a small corner (the guide's top-left). */
    compact: Boolean = false,
    hidden: List<HiddenPlaylist> = emptyList(),
    onOpenHidden: ((HiddenPlaylist) -> Unit)? = null,
) {
    if (choices.size + hidden.size < 2) return
    val tokens = MaterialTheme.nuvio
    var menuOpen by remember { mutableStateOf(false) }
    val current = choices.firstOrNull { it.id == selectedId }
    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(NuvioTokens.Radius.md))
                .background(tokens.colors.surface)
                .clickable { menuOpen = true }
                .padding(horizontal = if (compact) NuvioTokens.Space.s8 else NuvioTokens.Space.s12, vertical = if (compact) NuvioTokens.Space.s6 else NuvioTokens.Space.s10),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) NuvioTokens.Space.s2 else NuvioTokens.Space.s8),
        ) {
            if (!compact) Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null, tint = tokens.colors.textMuted, modifier = Modifier.size(NuvioTokens.Icon.md))
            Text(
                text = current?.name ?: stringResource(if (compact) Res.string.livetv_ui_all else Res.string.livetv_ui_all_playlists),
                style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!compact) Text(text = current?.detail ?: allDetail, style = MaterialTheme.typography.labelSmall, color = tokens.colors.textMuted, maxLines = 1)
            Icon(Icons.Filled.ExpandMore, contentDescription = null, tint = tokens.colors.textMuted, modifier = if (compact) Modifier.size(NuvioTokens.Icon.sm) else Modifier)
        }
        Menu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            MenuItem(text = stringResource(Res.string.livetv_ui_all_playlists), selected = current == null, onClick = { menuOpen = false; onSelect(null) })
            choices.forEach { c ->
                MenuItem(text = c.name, selected = current?.id == c.id, onClick = { menuOpen = false; onSelect(c.id) })
            }
            hidden.forEach { h ->
                HiddenPlaylistItem(h, onClick = onOpenHidden?.let { open -> { menuOpen = false; open(h) } })
            }
        }
    }
}

@Composable
private fun HiddenPlaylistItem(item: HiddenPlaylist, onClick: (() -> Unit)?) {
    val tokens = MaterialTheme.nuvio
    val reason = stringResource(if (item.profileHidden) Res.string.livetv_ui_playlist_profile_hidden else Res.string.livetv_ui_playlist_all_hidden)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTokens.Space.s8)
            .clip(tokens.shapes.compactCard)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(NuvioTokens.Space.s12),
    ) {
        Text(
            text = item.name,
            style = MaterialTheme.typography.bodyLarge,
            color = tokens.colors.textDisabled,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = if (onClick != null) reason + " · " + stringResource(Res.string.livetv_ui_tap_to_manage) else reason,
            style = MaterialTheme.typography.labelSmall,
            color = tokens.colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
