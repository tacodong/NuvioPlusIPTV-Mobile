package com.nuvio.app.features.livetv

import com.nuvio.app.features.livetv.data.EpgStore
import com.nuvio.app.features.livetv.data.LiveTvDirs
import com.nuvio.app.features.livetv.data.LiveTvFiles
import com.nuvio.app.features.livetv.model.EpgAssignment
import com.nuvio.app.features.livetv.model.EpgProgram
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class LiveTvIosStorageTest {
    @Test
    fun gzipGuideRoundTripsUtf8AcrossBufferBoundaryAndKeepsLastGoodFile() = runTest {
        val name = "guide-test-${Random.nextLong()}.xml"
        val before = "a".repeat(128 * 1024 - 1)
        val guide = before + "🏒 Québec"
        try {
            assertTrue(LiveTvFiles.writeText(LiveTvDirs.EPG, name, guide))
            assertFalse(LiveTvFiles.writeText(LiveTvDirs.EPG, name, guide))
            assertEquals(guide, LiveTvFiles.readSmall(LiveTvDirs.EPG, name))
            assertFalse(LiveTvFiles.writeTextStream(LiveTvDirs.EPG, name, publish = {}) {
                it("Stale profile must leave the existing guide intact")
            })
            assertEquals(guide, LiveTvFiles.readSmall(LiveTvDirs.EPG, name))
        } finally {
            LiveTvFiles.delete(LiveTvDirs.EPG, name)
        }
    }

    @Test
    fun guideIndexesChannelOrderAndSportsListings() {
        val first = EpgProgram(100, 200, "Hockey", description = "Live game", people = listOf("Name"))
        val second = EpgProgram(200, 300, "Soccer")
        try {
            EpgStore.replaceAll("test", mapOf("c" to listOf(second, first)), mapOf("c" to EpgAssignment("guide", "one")))
            assertEquals(first, EpgStore.nowNext(listOf("c"), 150).getValue("c").now)
            assertEquals(second, EpgStore.nowNext(listOf("c"), 150).getValue("c").next)
            assertEquals(listOf(first.copy(description = null, people = emptyList())), EpgStore.range(100, 200, light = true, listOf("c")).getValue("c"))
            assertEquals(listOf("c" to first), EpgStore.findByWords(0, 300, 0, listOf("hockey")))
            EpgStore.replaceChannels("test-2", setOf("c"), mapOf("c" to listOf(second)), emptyMap())
            assertEquals(listOf(second), EpgStore.channelRange("c", 0, 300))
            assertEquals("test-2", EpgStore.fingerprint())
        } finally {
            EpgStore.clear()
        }
    }
}
