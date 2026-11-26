package com.nuvio.app.features.livetv.ui.sports

import com.nuvio.app.features.livetv.sports.League
import com.nuvio.app.features.livetv.sports.SportsCatalog
import com.nuvio.app.features.livetv.sports.TeamInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** Publish cumulative matches in league order, with at most eight league loads in flight. */
internal fun teamPickerSearchResults(
    leagues: List<League>,
    normalizedQuery: String,
    teamsFor: suspend (League) -> List<TeamInfo>,
): Flow<List<TeamInfo>> = flow {
    if (normalizedQuery.length < 2 || leagues.isEmpty()) {
        emit(emptyList())
        return@flow
    }
    val seen = HashSet<Pair<String, String>>()
    val matches = ArrayList<TeamInfo>()
    val context = currentCoroutineContext()
    for (batch in leagues.chunked(8)) {
        context.ensureActive()
        val results = coroutineScope {
            batch.map { league ->
                async {
                    val teams = loadPickerTeams(league, teamsFor)
                    val worker = currentCoroutineContext()
                    teams.filterIndexed { index, team ->
                        if (index % 64 == 0) worker.ensureActive()
                        listOfNotNull(team.fullName, team.name, team.location, team.abbr)
                            .any { SportsCatalog.norm(it).contains(normalizedQuery) }
                    }
                }
            }.awaitAll()
        }
        for (teams in results) {
            teams.forEachIndexed { index, team ->
                if (index % 64 == 0) context.ensureActive()
                // The same club can appear in domestic and international competitions.
                if (seen.add(SportsCatalog.teamFamily(team.leagueId) to team.id)) matches += team
            }
        }
        emit(matches.toList())
    }
}.flowOn(Dispatchers.Default).buffer(0)

internal suspend fun loadPickerTeams(league: League, teamsFor: suspend (League) -> List<TeamInfo>): List<TeamInfo> =
    try {
        teamsFor(league)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptyList()
    }
