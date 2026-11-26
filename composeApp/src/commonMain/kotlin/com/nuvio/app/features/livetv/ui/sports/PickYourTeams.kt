package com.nuvio.app.features.livetv.ui.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.livetv.sports.League
import com.nuvio.app.features.livetv.sports.LeagueKind
import com.nuvio.app.features.livetv.sports.Sport
import com.nuvio.app.features.livetv.sports.SportsCatalog
import com.nuvio.app.features.livetv.sports.SportsFollows
import com.nuvio.app.features.livetv.sports.SportsRepository
import com.nuvio.app.features.livetv.sports.SportsSettings
import com.nuvio.app.features.livetv.sports.TeamInfo
import com.nuvio.app.features.livetv.ui.LiveTvAdaptiveSheet
import com.nuvio.app.features.livetv.ui.dismissLiveTvAdaptiveSheet
import com.nuvio.app.features.livetv.ui.isHoveredOrFocused
import com.nuvio.app.features.livetv.ui.isWideLiveTvWindow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_clear
import nuvio.composeapp.generated.resources.action_done
import nuvio.composeapp.generated.resources.action_retry
import nuvio.composeapp.generated.resources.livetv_sports_follow
import nuvio.composeapp.generated.resources.livetv_sports_following
import nuvio.composeapp.generated.resources.livetv_sports_pick_teams
import nuvio.composeapp.generated.resources.livetv_sports_picker_help
import nuvio.composeapp.generated.resources.livetv_sports_picker_no_leagues
import nuvio.composeapp.generated.resources.livetv_sports_picker_no_matches
import nuvio.composeapp.generated.resources.livetv_sports_picker_no_teams
import nuvio.composeapp.generated.resources.livetv_sports_picker_results
import nuvio.composeapp.generated.resources.livetv_sports_picker_search
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PickYourTeamsSheet(settings: SportsSettings, follows: SportsFollows, onDismiss: () -> Unit) {
    val repo = SportsRepository
    // Keep this order fixed while open: a follow toggle must not reorder search matches.
    val leagues = remember(settings) {
        val active = repo.activeLeagues(settings, follows).map { it.id }.toSet()
        SportsCatalog.ordered(settings)
            .filter { it.kind == LeagueKind.TEAMS && (it.espnPath != null || it.id.startsWith("tsdb/")) }
            .sortedByDescending { it.id in active }
    }
    LaunchedEffect(Unit) { if (!follows.setUp) repo.markSetUp() }
    PickYourTeamsSheet(leagues, follows, repo::teams, repo::toggleTeam, onDismiss)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PickYourTeamsSheet(
    leagues: List<League>,
    follows: SportsFollows,
    teamsFor: suspend (League) -> List<TeamInfo>,
    onToggle: (TeamInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val wide = isWideLiveTvWindow()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val bySport = remember(leagues) { leagues.groupBy { it.sport } }
    var sportId by rememberSaveable { mutableStateOf<String?>(null) }
    val sport = bySport.keys.firstOrNull { it.name == sportId } ?: bySport.keys.firstOrNull()
    val sportLeagues = bySport[sport].orEmpty()
    var leagueId by rememberSaveable(sport) { mutableStateOf<String?>(null) }
    val league = sportLeagues.firstOrNull { it.id == leagueId } ?: sportLeagues.firstOrNull()
    var query by rememberSaveable { mutableStateOf("") }
    val normalized = remember(query) { SportsCatalog.norm(query).trim() }
    val searching = normalized.length >= 2
    var retry by remember { mutableStateOf(0) }
    val searchLeagues = remember(leagues) {
        val nationalGroups = HashSet<String>()
        leagues.filter { !it.national || nationalGroups.add(it.nationalGroup) }
    }
    // Reset immediately on query/league changes; a cancelled load can't display stale teams.
    val browseLeague = league.takeUnless { searching }
    var teams by remember(searching, browseLeague, normalized.takeIf { searching }, searchLeagues, retry) {
        mutableStateOf<List<TeamInfo>>(emptyList())
    }
    var loading by remember(searching, browseLeague, normalized.takeIf { searching }, searchLeagues, retry) {
        mutableStateOf(searching || browseLeague != null)
    }
    LaunchedEffect(searching, browseLeague, normalized.takeIf { searching }, searchLeagues, retry) {
        if (searching) {
            delay(350)
            teamPickerSearchResults(searchLeagues, normalized, teamsFor).collect { teams = it }
        } else if (browseLeague != null) {
            teams = loadPickerTeams(browseLeague, teamsFor).distinctBy { it.key }
        }
        loading = false
    }

    LiveTvAdaptiveSheet(onDismissRequest = onDismiss, sheetState = sheetState, fullHeight = true, wide = wide, wideMaxWidth = 1060.dp) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 670.dp).fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Res.string.livetv_sports_pick_teams), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(Res.string.livetv_sports_picker_help), style = MaterialTheme.typography.bodySmall, color = tokens.colors.textSecondary)
                }
                DialogButton(text = stringResource(Res.string.action_done), style = DialogButtonStyle.Primary, onClick = {
                    scope.launch { dismissLiveTvAdaptiveSheet(sheetState, wide, onDismiss) }
                })
            }
            NuvioInputField(query, { query = it }, stringResource(Res.string.livetv_sports_picker_search), trailingContent = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.action_clear))
                }
            })
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (wide && !searching && sport != null) {
                    LazyColumn(Modifier.width(196.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(bySport.keys.toList(), key = { it.name }) { item ->
                            SportChip(item, sport, Modifier.fillMaxWidth()) { sportId = item.name }
                        }
                    }
                }
                Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (searching) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(Res.string.livetv_sports_picker_results), style = MaterialTheme.typography.titleSmall)
                            if (loading) NuvioLoadingIndicator(size = 16.dp)
                        }
                    } else {
                        if (!wide) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(bySport.keys.toList(), key = { it.name }) { item ->
                                SportChip(item, sport) { sportId = item.name }
                            }
                        }
                        key(sport) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(sportLeagues, key = { it.id }) { item ->
                                    Chip(item.name, selected = item == league, onClick = { leagueId = item.id })
                                }
                            }
                        }
                    }
                    if (!searching && loading) {
                        NuvioLoadingIndicator(Modifier.padding(16.dp).align(Alignment.CenterHorizontally))
                    } else if (!loading && teams.isEmpty()) {
                        val message = when {
                            searching -> Res.string.livetv_sports_picker_no_matches
                            browseLeague != null -> Res.string.livetv_sports_picker_no_teams
                            else -> Res.string.livetv_sports_picker_no_leagues
                        }
                        Text(stringResource(message), style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
                        if (browseLeague != null) Chip(stringResource(Res.string.action_retry), selected = false, onClick = { retry++ })
                    } else {
                        key(browseLeague?.id, normalized.takeIf { searching }) {
                            LazyVerticalGrid(
                                columns = if (wide) GridCells.Adaptive(216.dp) else GridCells.Fixed(1),
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                contentPadding = PaddingValues(bottom = if (wide) 4.dp else nuvioSafeBottomPadding(12.dp)),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(teams, key = { it.key }) { team ->
                                    val teamLeague = if (searching) leagues.firstOrNull { it.id == team.leagueId } else null
                                    TeamPickerCard(team, teamLeague, follows.followedTeam(team) != null) { onToggle(team) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SportChip(sport: Sport, selected: Sport?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Chip("${sport.icon}  ${sport.label}", selected = sport == selected, modifier = modifier, onClick = onClick)
}

@Composable
private fun TeamPickerCard(team: TeamInfo, league: League?, followed: Boolean, onToggle: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val shape = tokens.shapes.compactCard
    val interaction = remember { MutableInteractionSource() }
    val highlighted = interaction.isHoveredOrFocused()
    val state = stringResource(if (followed) Res.string.livetv_sports_following else Res.string.livetv_sports_follow)
    val detail = listOfNotNull(league?.short, team.location?.takeUnless { team.fullName.contains(it, ignoreCase = true) }, team.record)
        .filter { it.isNotBlank() }.joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().heightIn(min = 62.dp).clip(shape)
            .background(tokens.colors.surfaceCard)
            .background(teamBandBrush(team, if (highlighted || followed) 0.94f else 0.68f))
            .border(
                tokens.borders.thin,
                when {
                    highlighted -> tokens.colors.borderFocus
                    followed -> tokens.colors.borderSelected
                    else -> tokens.colors.borderDefault
                },
                shape,
            )
            .semantics { stateDescription = state }
            .toggleable(value = followed, interactionSource = interaction, indication = ripple(), role = Role.Checkbox) { onToggle() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TeamLogo(team, 34.dp)
        Column(Modifier.weight(1f)) {
            Text(team.fullName, style = MaterialTheme.typography.titleSmall, color = tokens.colors.textPrimary,
                fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(if (followed) Icons.Filled.Star else Icons.Outlined.StarBorder, contentDescription = null,
            tint = if (followed) tokens.colors.accent else tokens.colors.textSecondary, modifier = Modifier.size(20.dp))
    }
}
