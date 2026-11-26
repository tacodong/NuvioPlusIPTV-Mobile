package com.nuvio.app.features.livetv.data

import com.nuvio.app.features.livetv.model.EpgAssignment
import com.nuvio.app.features.livetv.model.EpgProgram
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpgStoreDesktopTest {
    private lateinit var dir: Path
    private val hour = 60 * 60 * 1000L
    private val t0 = 1_700_000_000_000L

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("epgstore-test")
        EpgStore.directoryForTests = dir
    }

    @AfterTest
    fun tearDown() {
        EpgStore.directoryForTests = null
        dir.toFile().deleteRecursively()
    }

    private fun show(startH: Int, lenH: Int, title: String, descr: String? = "About $title") = EpgProgram(
        startMs = t0 + startH * hour, stopMs = t0 + (startH + lenH) * hour, title = title, description = descr,
        category = "News", year = 2024, people = listOf("A", "B"), stalkerArchiveAvailable = true, stalkerSourceIdentity = "portal",
    )

    private val guide = mapOf(
        "a" to listOf(show(2, 1, "Late"), show(0, 2, "Morning")),
        "b" to listOf(show(0, 1, "Early"), show(1, 1, "Midday", descr = null)),
    )

    @Test
    fun guideSurvivesReopenWithFingerprintAndAutoMatches() {
        EpgStore.replaceAll("fp-1", guide, mapOf("a" to EpgAssignment("src", "xml.a")))
        EpgStore.directoryForTests = dir // closes and reopens the database file
        assertEquals("fp-1", EpgStore.fingerprint())
        assertEquals(4L, EpgStore.count())
        assertEquals(mapOf("a" to EpgAssignment("src", "xml.a")), EpgStore.autoMatches())
    }

    @Test
    fun rangeIsOrderedAndKeepsEveryField() {
        EpgStore.replaceAll("fp", guide, emptyMap())
        val rows = EpgStore.range(t0, t0 + 3 * hour)
        assertEquals(listOf("Morning", "Late"), rows.getValue("a").map { it.title })
        assertEquals(show(0, 2, "Morning"), rows.getValue("a").first())
        assertNull(rows.getValue("b")[1].description)
        val light = EpgStore.range(t0, t0 + 3 * hour, light = true, channels = listOf("a")).getValue("a").first()
        assertNull(light.description)
        assertEquals(emptyList(), light.people)
        assertEquals("portal", light.stalkerSourceIdentity)
    }

    @Test
    fun rangeIncludesShowsStartedBeforeTheWindow() {
        EpgStore.replaceAll("fp", guide, emptyMap())
        val rows = EpgStore.range(t0 + hour + 1, t0 + hour + 2, channels = listOf("a"))
        assertEquals(listOf("Morning"), rows.getValue("a").map { it.title })
    }

    @Test
    fun nowNextProgramAndChannelEdits() {
        EpgStore.replaceAll("fp", guide, emptyMap())
        val nn = EpgStore.nowNext(listOf("a", "b"), t0 + 30 * 60_000L)
        assertEquals("Morning", nn.getValue("a").now?.title)
        assertEquals("Late", nn.getValue("a").next?.title)
        assertEquals("Midday", EpgStore.program("b", t0 + hour)?.title)

        EpgStore.replaceChannels("fp-2", setOf("b"), mapOf("b" to listOf(show(0, 3, "Marathon"))), emptyMap())
        assertEquals("fp-2", EpgStore.fingerprint())
        assertEquals(listOf("Marathon"), EpgStore.channelRange("b", t0, t0 + 4 * hour).map { it.title })

        EpgStore.deleteChannels(listOf("a"))
        assertTrue(EpgStore.range(t0, t0 + 4 * hour).keys == setOf("b"))
        assertEquals(1, EpgStore.purgeOlderThan(t0 + 4 * hour))
        EpgStore.clear()
        assertEquals(0L, EpgStore.count())
        assertNull(EpgStore.fingerprint())
    }

    @Test
    fun searchAndScan() {
        EpgStore.replaceAll("fp", guide, emptyMap())
        val found = EpgStore.findByWords(t0, t0 + 4 * hour, t0, listOf("mid", "about late"), inDescription = true, limit = 10)
        assertEquals(setOf("Midday", "Late"), found.map { it.second.title }.toSet())
        val scanned = mutableListOf<String>()
        EpgStore.scanListings(t0, t0 + 4 * hour, t0) { _, _, _, title, _, _ -> scanned += title }
        assertEquals(listOf("Morning", "Early", "Midday", "Late"), scanned)
    }

    @Test
    fun scanPagesThroughLargeGuidesInStartOrderWithoutRepeats() {
        // Several pages of 500, with many rows sharing a start time across a page boundary.
        val big = (0 until 40).associate { ch ->
            "ch$ch" to (0 until 40).map { slot -> show(slot, 1, "ch$ch-$slot", descr = null) }
        }
        EpgStore.replaceAll("fp", big, emptyMap())
        val seen = mutableListOf<Pair<String, Long>>()
        EpgStore.scanListings(t0, t0 + 100 * hour, t0) { ch, start, _, _, _, _ -> seen += ch to start }
        assertEquals(1_600, seen.size)
        assertEquals(1_600, seen.toSet().size)
        assertEquals(seen.map { it.second }.sorted(), seen.map { it.second })
    }
}
