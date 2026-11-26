package com.nuvio.app.features.livetv.multiview

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The live player's Multiview button: opens Multiview with this channel in the first pane. It
 * carries a stable channel identity, never a stream request.
 */
internal val LocalOpenMultiview = staticCompositionLocalOf<((String?) -> Unit)?> { null }

/** Find in Nuvio from the player: leaves the player for the movie or series page ("movie"/"series", TMDB id, name). */
internal val LocalOpenNuvioTitle = staticCompositionLocalOf<((type: String, tmdbId: Int, title: String) -> Unit)?> { null }
