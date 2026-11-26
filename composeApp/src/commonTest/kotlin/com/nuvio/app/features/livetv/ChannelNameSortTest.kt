package com.nuvio.app.features.livetv

import com.nuvio.app.features.livetv.model.ChannelSort
import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.model.LiveTvSettings
import com.nuvio.app.features.livetv.model.LiveUserState
import com.nuvio.app.features.livetv.ui.ChannelNameSort
import com.nuvio.app.features.livetv.ui.LiveTvViewState
import kotlin.test.Test
import kotlin.test.assertEquals

class ChannelNameSortTest {
    private fun channel(key: String, name: String, number: Int = 1, group: String = "g") =
        LiveChannel(key, "pl", "Playlist", name, null, null, null, group, group, number, "http://h/$key.ts")

    private fun sorted(vararg names: String): List<String> =
        ChannelNameSort.sort(names.mapIndexed { i, n -> channel("k$i", n) }).map { it.name }

    @Test
    fun tagsInFrontDontCount() {
        assertEquals(
            listOf("US: ABC", "|UK| BBC One", "[HD] CNN", "★ Discovery", "US | ESPN", "4K Fox", "UK - Sky News"),
            sorted("US | ESPN", "UK - Sky News", "[HD] CNN", "★ Discovery", "|UK| BBC One", "4K Fox", "US: ABC")
        )
    }

    @Test
    fun numbersGoByValue() {
        assertEquals(listOf("Channel 2", "Channel 10", "Channel 100"), sorted("Channel 10", "Channel 100", "Channel 2"))
    }

    @Test
    fun fancyLettersCountAsPlainOnes() {
        assertEquals(listOf("𝐀𝐌𝐂", "Ｂｏｏｍｅｒａｎｇ", "ᴇsᴘɴ", "Zee"), sorted("Zee", "ᴇsᴘɴ", "Ｂｏｏｍｅｒａｎｇ", "𝐀𝐌𝐂"))
    }

    @Test
    fun realNamesStartingWithAShortWordStay() {
        assertEquals(listOf("ABC - East", "BBC One", "ESPN"), sorted("ESPN", "BBC One", "ABC - East"))
    }

    @Test
    fun nameSortWinsOverAHandMadeOrder() {
        val input = listOf(channel("d", "Delta", 4), channel("b", "Beta", 2), channel("a", "Alpha", 1), channel("c", "Charlie", 3))
        val user = LiveUserState(channelOrder = mapOf("g" to listOf("d", "c", "b", "a")))
        fun group(sort: ChannelSort) = LiveTvViewState.build(input, user, LiveTvSettings(channelSort = sort), hasSources = true)
            .sections.first { it.group.id == "g" }.channels.map { it.key }
        assertEquals(listOf("a", "b", "c", "d"), group(ChannelSort.NAME))
        assertEquals(listOf("a", "b", "c", "d"), group(ChannelSort.NUMBER))
        assertEquals(listOf("d", "c", "b", "a"), group(ChannelSort.PLAYLIST))
    }
}
