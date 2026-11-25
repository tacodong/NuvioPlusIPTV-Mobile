package com.nuvio.app.features.livetv.ui.sports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.livetv.sports.SportsEvent

internal fun sportsGameColumns(width: Dp): Int = ((width + 10.dp) / 260.dp).toInt().coerceIn(2, 6)

/** Lazy rows share the vertical list with the league headings, so the viewport stays virtualized. */
internal fun LazyListScope.sportsGameRows(
    section: String,
    games: List<SportsEvent>,
    columns: Int,
    card: @Composable (SportsEvent, Modifier) -> Unit,
) {
    val rows = games.chunked(columns)
    items(rows.size, key = { index -> "$section/${rows[index].first().id}" }, contentType = { "sports-game-row" }) { index ->
        val row = rows[index]
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { game -> key(game.id) { card(game, Modifier.weight(1f)) } }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}
