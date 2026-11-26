package com.nuvio.app.features.livetv.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.livetv.data.LiveTvPreferences
import com.nuvio.app.features.livetv.model.LiveChannel
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.action_save
import org.jetbrains.compose.resources.stringResource
import nuvio.composeapp.generated.resources.livetv_ui_channels_n
import nuvio.composeapp.generated.resources.livetv_ui_clear
import nuvio.composeapp.generated.resources.livetv_ui_favs_added
import nuvio.composeapp.generated.resources.livetv_ui_favs_in_group
import nuvio.composeapp.generated.resources.livetv_ui_favs_removed
import nuvio.composeapp.generated.resources.livetv_ui_n_picked
import nuvio.composeapp.generated.resources.livetv_ui_pick_favorites
import nuvio.composeapp.generated.resources.livetv_ui_select_all
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.getPluralString

/**
 * Pick favorites (several at once): a group's channels with a star on each, Select all / Clear,
 * and one Save. Of [channels], exactly the starred ones are favorites afterwards.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PickFavoritesSheet(
    groupTitle: String,
    channels: List<LiveChannel>,
    favoriteKeys: Set<String>,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val wide = isWideLiveTvWindow()
    var picked by remember { mutableStateOf(channels.map { it.key }.filter { it in favoriteKeys }.toSet()) }
    fun close(then: () -> Unit = {}) { scope.launch { dismissLiveTvAdaptiveSheet(sheetState, wide) { onDismiss(); then() } } }

    LiveTvAdaptiveSheet(onDismissRequest = onDismiss, sheetState = sheetState, wide = wide) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(Res.string.livetv_ui_pick_favorites), style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold)
                Text(
                    groupTitle + " · " + pluralStringResource(Res.plurals.livetv_ui_channels_n, channels.size, channels.size),
                    style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Text(stringResource(Res.string.livetv_ui_n_picked, picked.size), style = MaterialTheme.typography.labelLarge, color = tokens.colors.accent)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s4),
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
        ) {
            DialogButton(text = stringResource(Res.string.livetv_ui_select_all), style = DialogButtonStyle.Secondary, onClick = { picked = channels.map { it.key }.toSet() })
            DialogButton(text = stringResource(Res.string.livetv_ui_clear), style = DialogButtonStyle.Secondary, onClick = { picked = emptySet() })
        }
        NuvioBottomSheetDivider()
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
            items(channels, key = { it.key }) { c ->
                val on = c.key in picked
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable { picked = if (on) picked - c.key else picked + c.key }
                        .padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s8),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
                ) {
                    ChannelLogo(c, size = 40.dp)
                    Text(
                        c.name, style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = if (on) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = null,
                        tint = if (on) tokens.colors.accent else tokens.colors.textMuted,
                    )
                }
            }
        }
        NuvioBottomSheetDivider()
        DialogButtons {
            DialogButton(text = stringResource(Res.string.action_cancel), onClick = { close() })
            DialogButton(
                text = stringResource(Res.string.action_save), style = DialogButtonStyle.Primary,
                onClick = {
                    val before = channels.count { it.key in favoriteKeys }
                    val after = picked.size
                    LiveTvPreferences.setFavoritesIn(channels.map { it.key }, picked)
                    scope.launch {
                        NuvioToastController.show(
                            when {
                                after == before -> getPluralString(Res.plurals.livetv_ui_favs_in_group, after, after)
                                after > before -> getString(Res.string.livetv_ui_favs_added, after - before)
                                else -> getString(Res.string.livetv_ui_favs_removed, before - after)
                            },
                        )
                    }
                    close()
                },
            )
        }
    }
}
