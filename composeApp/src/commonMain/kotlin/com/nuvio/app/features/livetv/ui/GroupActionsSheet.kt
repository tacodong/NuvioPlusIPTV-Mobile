package com.nuvio.app.features.livetv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioBottomSheetActionRow
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.livetv.data.LiveTvPreferences
import com.nuvio.app.features.livetv.model.ChannelGroup
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.livetv_add_favorite_group
import nuvio.composeapp.generated.resources.livetv_remove_favorite_group
import org.jetbrains.compose.resources.stringResource

/** Hold on a group card: star it as a favorite group (listed first), or take the star off. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupActionsSheet(group: ChannelGroup, onDismiss: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val wide = isWideLiveTvWindow()
    val user by LiveTvPreferences.userState.collectAsStateWithLifecycle()
    val favorite = group.id in user.favoriteGroups
    fun close(then: () -> Unit = {}) {
        scope.launch { dismissLiveTvAdaptiveSheet(sheetState, wide) { onDismiss(); then() } }
    }
    LiveTvAdaptiveSheet(onDismissRequest = onDismiss, sheetState = sheetState, wide = wide, scrollContent = true) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s12),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
        ) {
            GroupIconBox(group, size = 40.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(group.title, style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        NuvioBottomSheetDivider()
        NuvioBottomSheetActionRow(
            title = stringResource(if (favorite) Res.string.livetv_remove_favorite_group else Res.string.livetv_add_favorite_group),
            icon = if (favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
            onClick = { LiveTvPreferences.setGroupFavorite(group.id, !favorite); close() },
        )
        Spacer(Modifier.height(NuvioTokens.Space.s12))
    }
}
