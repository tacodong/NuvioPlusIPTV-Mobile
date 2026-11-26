package com.nuvio.app.features.livetv.ui

import com.nuvio.app.features.livetv.data.LiveTvRepository
import com.nuvio.app.features.livetv.data.TitleMatchFixes
import com.nuvio.app.features.livetv.model.EpgProgram
import com.nuvio.app.features.livetv.record.RecordingRules

/**
 * "Find in Nuvio" from a show in the guide (like the TV app): the movie or series it clearly is
 * on TMDB, so its page opens straight away. With no clear match, a list of likely titles to pick from.
 */
internal object NuvioTitleFind {
    private val EPISODE_CODE = Regex("""(?i)\bs\d{1,3}\s*[.\- ]?\s*e\d{1,4}\b|\b\d{1,2}x\d{1,3}\b""")
    private val TRAILING_YEAR = Regex("""\s*[(\[]\s*((?:19|20)\d{2})\s*[)\]]\s*$""")
    private val LEADING_TAG = Regex("""^\s*(new|live|premiere|repeat|re-?run|encore|movie|film)\s*[:!\-–]\s*""", RegexOption.IGNORE_CASE)
    private val SHOW_SPLIT = Regex("""\s+[-–:|]\s+""")
    private val MOVIE_WORDS = Regex("""\b(movie|movies|film|films|cinema|feature)\b""", RegexOption.IGNORE_CASE)
    private val SERIES_WORDS = Regex("""\b(series|sitcom|episode|episodes|soap|talk show|reality|news|game show)\b""", RegexOption.IGNORE_CASE)
    private val SPORTS_CHANNEL = Regex("""(?i)sport|espn|dazn|bein|\bnfl\b|\bnba\b|\bnhl\b|\bmlb\b|\bufc\b|\bf1\b|ppv""")
    private val GAME_TITLE = Regex("""(?i)\s(vs?\.?|@)\s""")

    /** Games and other sports events have no movie or series page. */
    fun canLookUp(program: EpgProgram, channelName: String?): Boolean =
        !LiveTvRepository.isPlaceholderTitle(program.title) &&
            !GAME_TITLE.containsMatchIn(program.title) &&
            !(channelName != null && SPORTS_CHANNEL.containsMatchIn(channelName))

    /** Show (true), movie (false) or can't tell (null), from the episode number, category and length. */
    fun seriesHint(program: EpgProgram): Boolean? {
        if (!program.episode.isNullOrBlank()) return true
        program.category?.let { c ->
            if (MOVIE_WORDS.containsMatchIn(c)) return false
            if (SERIES_WORDS.containsMatchIn(c)) return true
        }
        val minutes = (program.stopMs - program.startMs) / 60_000
        return when {
            minutes in 1..65 -> true
            minutes >= 80 -> false
            else -> null
        }
    }

    /** The year in the title ("Heat (1995)"), else the guide's own year for a movie. */
    fun yearOf(program: EpgProgram, series: Boolean?): Int? =
        TRAILING_YEAR.find(program.title)?.groupValues?.get(1)?.toIntOrNull() ?: program.year.takeIf { series == false }

    /** What to look up: the title without NEW/LIVE marks, year and episode code; for a show, the show's name. */
    fun queryOf(program: EpgProgram, series: Boolean?): String {
        var t = RecordingRules.cleanTitle(program.title)
        t = LEADING_TAG.replace(t, "").replace(TRAILING_YEAR, "").replace(EPISODE_CODE, " ")
            .replace(Regex("""\s+"""), " ").trim()
        // "Show - Episode name": the show.
        if (series == true) SHOW_SPLIT.split(t, limit = 2).firstOrNull()?.trim()?.takeIf { it.length >= 2 }?.let { t = it }
        return t.trim(' ', '-', ':', '|').ifBlank { program.title.trim() }
    }

    private fun norm(s: String): String = s.lowercase().replace("&", "and").filter { it.isLetterOrDigit() }

    /**
     * The one title with this exact name (the best known, when several share it and one is far
     * better known than the rest), or null when it isn't clear.
     */
    suspend fun clearMatch(program: EpgProgram): TitleMatchFixes.Candidate? {
        val series = seriesHint(program)
        val query = queryOf(program, series)
        if (query.length < 2) return null
        val year = yearOf(program, series)
        val results = runCatching { TitleMatchFixes.search(query, series, year) }.getOrDefault(emptyList())
        val wanted = norm(query)
        val exact = results.filter { norm(it.name) == wanted }
            .filter { year == null || it.year == null || kotlin.math.abs(it.year - year) <= 1 }
            .sortedByDescending { it.votes }
        val best = exact.firstOrNull() ?: return null
        val next = exact.getOrNull(1) ?: return best
        return best.takeIf { it.votes >= 50 && it.votes >= next.votes * 3 }
    }
}
