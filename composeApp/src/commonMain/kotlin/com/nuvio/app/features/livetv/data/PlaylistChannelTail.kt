package com.nuvio.app.features.livetv.data

import com.nuvio.app.features.livetv.model.LiveChannel

/** Tracks only positions while a saved M3U is read straight into the shared channel list. */
internal class PlaylistChannelTail(
    private val channels: MutableList<LiveChannel>,
    private val usedKeys: MutableSet<String>,
    private val firstNumber: Int,
) {
    private val from = channels.size
    /** One flag per channel this playlist added: its link had no /live/ marker. */
    private var unmarked = BooleanArray(1024)
    var removed: Int = 0
        private set

    fun unmarkedEntry() {
        val at = channels.size - from
        if (at >= unmarked.size) unmarked = unmarked.copyOf(maxOf(at + 1, unmarked.size * 2))
        unmarked[at] = true
    }

    private fun isUnmarked(at: Int): Boolean = at < unmarked.size && unmarked[at]

    /** A failed read must not leave partly read channels or taken keys behind. */
    fun rollback() {
        for (i in from until channels.size) usedKeys.remove(channels[i].key)
        channels.subList(from, channels.size).clear()
    }

    /**
     * The whole playlist's marked/unmarked ratio decides whether unmarked episode-named entries
     * are strays. Only then are this playlist's numbers and key suffixes set again, as if the
     * strays had never been there.
     */
    fun removeStrays(nextNumber: Int, episodeName: (String) -> Boolean): Int {
        val count = channels.size - from
        val stray = BooleanArray(count) { isUnmarked(it) && episodeName(channels[from + it].name) }
        removed = stray.count { it }
        if (removed == 0) return nextNumber

        for (i in from until channels.size) usedKeys.remove(channels[i].key)
        var counter = firstNumber
        var write = from
        for (read in from until channels.size) {
            if (stray[read - from]) continue
            val channel = channels[read]
            val baseKey = "${channel.sourceId}|${channel.tvgId ?: ""}|${channel.name}"
            var key = baseKey
            var suffix = 2
            while (!usedKeys.add(key)) key = "$baseKey#${suffix++}"
            val number = if (channel.autoNumber) counter else channel.number
            counter = maxOf(counter, number) + 1
            channels[write++] = if (key == channel.key && number == channel.number) channel
                else channel.copy(key = key, number = number)
        }
        channels.subList(write, channels.size).clear()
        return counter
    }
}
