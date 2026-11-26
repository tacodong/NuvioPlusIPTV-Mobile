package com.nuvio.app.features.livetv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.core.ui.NuvioBottomSheetActionRow
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.livetv.data.TitleMatchFixes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.livetv_fix_match
import nuvio.composeapp.generated.resources.livetv_fix_match_all
import nuvio.composeapp.generated.resources.livetv_fix_match_auto
import nuvio.composeapp.generated.resources.livetv_fix_match_current
import nuvio.composeapp.generated.resources.livetv_fix_match_hint
import nuvio.composeapp.generated.resources.livetv_fix_match_movie
import nuvio.composeapp.generated.resources.livetv_fix_match_movies
import nuvio.composeapp.generated.resources.livetv_fix_match_none
import nuvio.composeapp.generated.resources.livetv_fix_match_show
import nuvio.composeapp.generated.resources.livetv_fix_match_shows
import nuvio.composeapp.generated.resources.livetv_fix_match_year
import org.jetbrains.compose.resources.stringResource

/**
 * "Fix match" (like Plex and Emby, and the TV app): search for the right show or movie and tap
 * it. It searches as you type; All / Shows / Movies and a year narrow it, and an IMDb id or a
 * TMDB link finds that exact title.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FixMatchSheet(
    title: String,
    scopeNote: String,
    key: String,
    initialQuery: String,
    initialSeries: Boolean?,
    initialYear: Int? = null,
    onDismiss: () -> Unit,
    /** Find in Nuvio: the sheet only picks a title (nothing saved as a fix), with this heading. */
    heading: String? = null,
    onPicked: ((TitleMatchFixes.Candidate) -> Unit)? = null,
) {
    val tokens = MaterialTheme.nuvio
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val pick = remember(key) { if (onPicked != null) null else TitleMatchFixes.get(key) }
    var query by remember { mutableStateOf(pick?.name ?: initialQuery) }
    var yearText by remember { mutableStateOf(if (pick == null) initialYear?.toString().orEmpty() else "") }
    var series by remember { mutableStateOf(initialSeries) }
    var results by remember { mutableStateOf<List<TitleMatchFixes.Candidate>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    fun close(then: () -> Unit = {}) {
        then()
        scope.launch { dismissNuvioBottomSheet(sheetState) { onDismiss() } }
    }

    LaunchedEffect(query, yearText, series) {
        val q = query.trim()
        if (q.isEmpty()) { results = emptyList(); searched = false; return@LaunchedEffect }
        delay(450) // typing: wait for a pause
        searching = true
        results = runCatching { TitleMatchFixes.search(q, series, yearText.trim().toIntOrNull()) }.getOrDefault(emptyList())
        searching = false
        searched = true
    }

    NuvioModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, fullHeight = true) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s8),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
        ) {
            Text(heading ?: stringResource(Res.string.livetv_fix_match), style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold)
            Text("$title  ·  $scopeNote", style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    NuvioInputField(value = query, onValueChange = { query = it }, placeholder = stringResource(Res.string.livetv_fix_match_hint))
                }
                Box(Modifier.width(96.dp)) {
                    NuvioInputField(value = yearText, onValueChange = { v -> yearText = v.filter { it.isDigit() }.take(4) }, placeholder = stringResource(Res.string.livetv_fix_match_year))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8)) {
                Chip(label = stringResource(Res.string.livetv_fix_match_all), selected = series == null, onClick = { series = null })
                Chip(label = stringResource(Res.string.livetv_fix_match_shows), selected = series == true, onClick = { series = true })
                Chip(label = stringResource(Res.string.livetv_fix_match_movies), selected = series == false, onClick = { series = false })
            }
        }
        if (pick != null) {
            NuvioBottomSheetActionRow(title = stringResource(Res.string.livetv_fix_match_auto), icon = Icons.Filled.Refresh, onClick = {
                close { TitleMatchFixes.pick(key, null) }
            })
        }
        Box(Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 560.dp)) {
            when {
                searching && results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { NuvioLoadingIndicator() }
                results.isEmpty() && searched -> Text(
                    stringResource(Res.string.livetv_fix_match_none),
                    style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted,
                    modifier = Modifier.padding(NuvioTokens.Space.s16),
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(start = NuvioTokens.Space.s16, end = NuvioTokens.Space.s16, top = NuvioTokens.Space.s8, bottom = com.nuvio.app.core.ui.nuvioSafeBottomPadding(NuvioTokens.Space.s16)),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
                    verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s16),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(results, key = { (if (it.series) "tv" else "mv") + it.tmdbId }) { c ->
                        val current = pick != null && pick.name.equals(c.name, ignoreCase = true) && pick.year == c.year && pick.series == c.series
                        MatchCard(c, current) { close { if (onPicked != null) onPicked(c) else TitleMatchFixes.pick(key, c) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun MatchCard(c: TitleMatchFixes.Candidate, current: Boolean, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(NuvioTokens.Radius.md)
    Column(Modifier.fillMaxWidth().clip(shape).clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(shape).background(tokens.colors.surfaceCard)
                .then(if (current) Modifier.border(2.dp, tokens.colors.borderFocus, shape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (c.poster != null) AsyncImage(model = c.poster, contentDescription = c.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(c.name, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textSecondary, maxLines = 4, modifier = Modifier.padding(NuvioTokens.Space.s8))
            if (current) Text(
                stringResource(Res.string.livetv_fix_match_current),
                style = MaterialTheme.typography.labelSmall, color = tokens.colors.textPrimary, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(NuvioTokens.Space.s4)
                    .clip(RoundedCornerShape(NuvioTokens.Radius.sm)).background(tokens.colors.surfaceSheet).padding(horizontal = NuvioTokens.Space.s6, vertical = 2.dp),
            )
        }
        Spacer(Modifier.height(NuvioTokens.Space.s4))
        Text(c.name, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textPrimary, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(
                stringResource(if (c.series) Res.string.livetv_fix_match_show else Res.string.livetv_fix_match_movie),
                c.year?.toString(),
                c.countries.take(2).joinToString("/").ifBlank { null },
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall, color = tokens.colors.textMuted, maxLines = 1,
        )
    }
}
