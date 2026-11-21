package com.nuvio.app.features.livetv.data

import com.nuvio.app.features.livetv.model.EpgAssignment
import com.nuvio.app.features.livetv.model.EpgProgram
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** Indexes the saved XMLTV guide in memory. LiveTvRepository rebuilds it from disk on launch. */
internal actual object EpgStore {
    private data class Guide(
        val fingerprint: String? = null,
        val programs: Map<String, List<EpgProgram>> = emptyMap(),
        val auto: Map<String, EpgAssignment> = emptyMap(),
    )

    private val lock = SynchronizedObject()
    private val guide = atomic(Guide())

    actual fun replaceAll(fingerprint: String, programs: Map<String, List<EpgProgram>>, auto: Map<String, EpgAssignment>) {
        synchronized(lock) {
            guide.value = Guide(fingerprint, ordered(programs), auto.toMap())
        }
    }

    actual fun replaceChannels(
        fingerprint: String, channels: Set<String>,
        programs: Map<String, List<EpgProgram>>, auto: Map<String, EpgAssignment>,
    ) {
        synchronized(lock) {
            val previous = guide.value
            guide.value = Guide(
                fingerprint,
                previous.programs.filterKeys { it !in channels } + ordered(programs.filterKeys { it in channels }),
                previous.auto.filterKeys { it !in channels } + auto.filterKeys { it in channels },
            )
        }
    }

    private fun ordered(programs: Map<String, List<EpgProgram>>) =
        programs.mapValues { (_, rows) -> rows.sortedBy { it.startMs } }

    actual fun fingerprint(): String? = guide.value.fingerprint

    actual fun range(fromMs: Long, toMs: Long, light: Boolean, channels: Collection<String>?): Map<String, List<EpgProgram>> {
        val programs = guide.value.programs
        val keys = channels ?: programs.keys
        return buildMap {
            for (key in keys) {
                val rows = programs[key].orEmpty().asSequence()
                    .takeWhile { it.startMs < toMs }.filter { it.stopMs > fromMs }
                    .map { row -> if (light) row.copy(description = null, people = emptyList()) else row }
                    .toList()
                if (rows.isNotEmpty()) put(key, rows)
            }
        }
    }

    actual fun channelRange(ch: String, fromMs: Long, toMs: Long): List<EpgProgram> =
        range(fromMs, toMs, false, listOf(ch))[ch].orEmpty()

    actual fun program(ch: String, startMs: Long): EpgProgram? =
        guide.value.programs[ch]?.firstOrNull { it.startMs == startMs }

    actual fun findByWords(
        fromMs: Long, toMs: Long, minStopMs: Long, words: Collection<String>,
        inDescription: Boolean, limit: Int,
    ): List<Pair<String, EpgProgram>> {
        val clean = words.map { it.trim().lowercase() }.filter { it.length >= 3 }.distinct()
        if (clean.isEmpty() || limit <= 0) return emptyList()
        val out = ArrayList<Pair<String, EpgProgram>>()
        for ((channel, rows) in guide.value.programs) {
            for (row in rows) {
                if (row.startMs >= toMs) break
                if (row.startMs < fromMs || row.stopMs <= minStopMs) continue
                val text = (row.title + if (inDescription) " " + row.description.orEmpty() else "").lowercase()
                if (clean.any { it in text }) {
                    out.add(channel to row)
                    if (out.size == limit) return out
                }
            }
        }
        return out
    }

    actual fun scanListings(
        fromMs: Long, toMs: Long, minStopMs: Long,
        onRow: (ch: String, start: Long, stop: Long, title: String, descr: String?, cat: String?) -> Unit,
    ) {
        for ((channel, rows) in guide.value.programs) {
            for (row in rows) {
                if (row.startMs >= toMs) break
                if (row.startMs >= fromMs && row.stopMs > minStopMs) {
                    onRow(channel, row.startMs, row.stopMs, row.title, row.description, row.category)
                }
            }
        }
    }

    actual fun nowNext(channels: Collection<String>, nowMs: Long): Map<String, NowNext> {
        val programs = guide.value.programs
        return buildMap {
            for (channel in channels) {
                val rows = programs[channel] ?: continue
                val now = rows.firstOrNull { it.isLive(nowMs) }
                val next = rows.firstOrNull { it.startMs >= (now?.stopMs ?: nowMs) }
                put(channel, NowNext(now, next))
            }
        }
    }

    actual fun autoMatches(): Map<String, EpgAssignment> = guide.value.auto

    actual fun deleteChannels(channels: Collection<String>) {
        val deleted = channels.toSet()
        synchronized(lock) {
            val previous = guide.value
            guide.value = previous.copy(
                programs = previous.programs.filterKeys { it !in deleted },
                auto = previous.auto.filterKeys { it !in deleted },
            )
        }
    }

    actual fun purgeOlderThan(beforeMs: Long): Int = synchronized(lock) {
        val previous = guide.value
        var removed = 0
        val programs = previous.programs.mapValues { (_, rows) ->
            rows.filter { it.stopMs >= beforeMs }.also { removed += rows.size - it.size }
        }.filterValues { it.isNotEmpty() }
        guide.value = previous.copy(programs = programs)
        removed
    }

    actual fun compact() {}

    actual fun clear() {
        synchronized(lock) { guide.value = Guide() }
    }

    actual fun count(): Long = guide.value.programs.values.sumOf { it.size.toLong() }
}
