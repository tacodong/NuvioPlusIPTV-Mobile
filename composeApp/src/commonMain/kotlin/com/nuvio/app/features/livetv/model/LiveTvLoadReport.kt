package com.nuvio.app.features.livetv.model

import com.nuvio.app.core.auth.currentDeviceClientMetadata
import com.nuvio.app.core.build.AppVersionConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.TimeSource

/**
 * What Live TV did since the app started, with timings, for the Start-Up Report (Live TV
 * settings > Help): whether the saved channel list and guide were used and why not, playlist
 * and guide updates, Sports, On Demand, recordings, sync and playback problems. The same
 * report as the TV app's, so a report from a phone reads like one from a TV.
 */
object LiveTvLoadReport {
    private val startedAt = TimeSource.Monotonic.markNow()
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun add(text: String) {
        val line = "+${seconds(startedAt.elapsedNow().inWholeMilliseconds)}s  $text"
        _lines.update { (it + line).takeLast(MAX_LINES) }
    }

    /** The first line of every report: the app version, so it's clear which build wrote it. */
    val buildLine: String
        get() = "Build: phone app ${AppVersionConfig.VERSION_NAME} (${AppVersionConfig.VERSION_CODE})"

    /** The device, for the top of the short report. */
    val deviceLine: String
        get() = runCatching { currentDeviceClientMetadata().let { "${it.platform} · ${it.deviceName}" } }.getOrDefault("unknown device")

    /** The whole report, as it's copied or shared. */
    fun fullText(): String = buildString {
        append(buildLine).append('\n').append(deviceLine)
        _lines.value.forEach { append('\n').append(it) }
    }

    /**
     * The report cut down to fit one Discord message (2,000 characters) without losing what
     * matters: lines that repeat are kept once with how many times ("×12"), problems and
     * timings always stay, and routine lines go first (oldest first) when it's still too long.
     */
    fun shortText(limit: Int = 1_900): String = ReportShortener.shorten(
        header = "phone ${AppVersionConfig.VERSION_NAME} (${AppVersionConfig.VERSION_CODE}) · $deviceLine",
        lines = _lines.value,
        limit = limit,
    )

    /** Milliseconds as seconds with one decimal ("12.3"). */
    internal fun seconds(ms: Long): String {
        val tenths = ms.coerceAtLeast(0) / 100
        return "${tenths / 10}.${tenths % 10}"
    }

    private const val MAX_LINES = 160
}

/** Shortens reports for pasting (see [LiveTvLoadReport.shortText]). */
internal object ReportShortener {
    private val TIME = Regex("""^\+(\d+)(?:\.(\d))?s\s+""")
    private val NUMBERS = Regex("""\d+(?:[.,]\d+)*""")
    private val SAMPLE_READERS = Regex("""\bR\d+\{""")
    private val PROBLEM = Regex(
        """\b(fail|failed|couldn'?t|could not|error|timed? ?out|refused|won'?t|denied|missing|out of date|froze|crash|exception|not found|invalid|unreachable|no space|expired|expires|blocked|http [45]\d\d|[45]\d\d)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val TIMING = Regex(
        """(\bin \d+(?:\.\d+)? ?(?:s|ms|min)\b|finished|ready|stored|searched|waited|read and set aside|took)""",
        RegexOption.IGNORE_CASE,
    )
    private val NOISE = listOf("display already at", "Assign EPG lists:")

    private class Line(val index: Int, val time: String, val text: String, val priority: Int, val count: Int)

    fun shorten(header: String, lines: List<String>, limit: Int): String {
        // Parse, and keep each repeated line once (its last time), with how often it came.
        val byKey = LinkedHashMap<String, Line>()
        lines.forEachIndexed { i, raw ->
            val m = TIME.find(raw)
            val whole = m?.groupValues?.get(1)?.toLongOrNull()
            val time = when {
                whole == null -> ""
                whole < 10 -> "$whole.${m.groupValues[2].ifEmpty { "0" }}s"
                else -> "${whole}s"
            }
            var text = if (m != null) raw.substring(m.value.length) else raw
            val cap = if (text.startsWith("Multiview sample:") || text.startsWith("VPN: vpn_traffic:")) 700 else 220
            if (text.length > cap) text = text.take(cap - 3) + "..."
            val prio = when {
                PROBLEM.containsMatchIn(text) -> 0
                NOISE.any { text.contains(it) } -> 3
                TIMING.containsMatchIn(text) -> 1
                else -> 2
            }
            val readerIds = if (text.startsWith("Multiview sample:")) SAMPLE_READERS.findAll(text).joinToString(",") { it.value } else ""
            val key = readerIds + NUMBERS.replace(text, "#")
            val prev = byKey.remove(key)
            byKey[key] = Line(i, time, text, prio, (prev?.count ?: 0) + 1)
        }
        val kept = byKey.values.sortedBy { it.index }.toMutableList()
        fun render(list: List<Line>, dropped: Int): String = buildString {
            append(header)
            list.forEach { l ->
                append('\n')
                if (l.time.isNotEmpty()) append(l.time).append(' ')
                append(l.text)
                if (l.count > 1) append(" (×").append(l.count).append(')')
            }
            if (dropped > 0) append("\n(").append(dropped).append(" routine lines left out; the full report is on the same page)")
        }
        var dropped = 0
        var out = render(kept, 0)
        // Too long: routine lines go first, then plain ones, then timings (oldest first each time).
        while (out.length > limit && kept.size > 1) {
            val victim = kept.filter { it.priority > 0 }.maxWithOrNull(compareBy<Line>({ it.priority }, { -it.index }))
                ?: kept.first()
            kept.remove(victim)
            dropped++
            out = render(kept, dropped)
        }
        return if (out.length > limit) out.take(limit - 3) + "..." else out
    }
}
