package com.nuvio.app.features.livetv.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.LocalScreenActive
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.livetv.data.M3uParser
import com.nuvio.app.features.livetv.data.NowNext
import com.nuvio.app.features.livetv.data.XmltvParser
import com.nuvio.app.features.livetv.model.EpgProgram
import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.model.LiveTvSettings
import com.nuvio.app.features.livetv.model.LiveUserState
import com.nuvio.app.features.livetv.sports.FollowedTeam
import com.nuvio.app.features.livetv.sports.GameState
import com.nuvio.app.features.livetv.sports.League
import com.nuvio.app.features.livetv.sports.Side
import com.nuvio.app.features.livetv.sports.Sport
import com.nuvio.app.features.livetv.sports.SportsDates
import com.nuvio.app.features.livetv.sports.SportsEvent
import com.nuvio.app.features.livetv.sports.SportsFollows
import com.nuvio.app.features.livetv.sports.SportsSettings
import com.nuvio.app.features.livetv.sports.TeamInfo
import com.nuvio.app.features.livetv.ui.sports.AllEventsTab
import com.nuvio.app.features.livetv.ui.sports.PickYourTeamsSheet
import com.nuvio.app.features.livetv.ui.sports.SportsActions
import com.nuvio.app.features.livetv.ui.sports.SportsSearchBar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Offscreen, no video, downloads, credentials or provider network needed. Set NUVIO_LAYOUT_RENDER_DIR to also write PNGs. */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class LiveTvDesktopRenderTest {
    private val output = System.getenv("NUVIO_LAYOUT_RENDER_DIR")?.let(Path::of)
    private val now = Instant.parse("2026-02-14T16:20:00Z").toEpochMilli()
    private val hour = 3_600_000L
    private val start = (now / hour) * hour - hour
    private val end = start + 12 * hour
    private val names = listOf("Metro News", "World Report", "City Desk", "Business Today", "Weather Central", "Public Media", "News Extra", "Market Watch", "Global Dispatch", "Current Affairs", "Metro Sports", "Court Vision", "Arena One", "Stadium Network", "Fieldhouse", "Culture Line", "Cinema Select", "Arts Now")
    private val channels = M3uParser.parse(buildString {
        appendLine("#EXTM3U")
        names.forEachIndexed { i, name ->
            appendLine("""#EXTINF:-1 tvg-id="station-$i" group-title="${when (i) { in 0..9 -> "News & Local"; in 10..14 -> "Sports"; else -> "Entertainment" }}",$name""")
            appendLine("https://fixture.invalid/channel-$i.m3u8")
        }
    }).entries.mapIndexed { i, entry ->
        LiveChannel("channel-$i", "fixture", "Sample IPTV", entry.name, entry.tvgId, entry.tvgName, null, "fixture:${entry.group}", entry.group.orEmpty(), i + 1, entry.url)
    }
    private val sections = LiveTvViewState.build(channels, LiveUserState(), LiveTvSettings(), hasSources = true).sections
    private val news = sections.first { it.group.title == "News & Local" }
    private val guide = run {
        val clock = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(ZoneOffset.UTC)
        val titles = listOf("Headlines", "The Afternoon Brief", "Across the City", "Community Desk", "News at Five", "Studio Live", "Evening Edition", "The Late Report")
        val xml = buildString {
            append("<tv>")
            channels.forEach { append("<channel id=\"${it.tvgId}\"><display-name>${it.name}</display-name></channel>") }
            channels.forEachIndexed { row, channel ->
                var begin = start
                repeat(18) { i ->
                    val stop = begin + ((i + row) % 3 + 1) * hour / 3
                    append("<programme start=\"${clock.format(Instant.ofEpochMilli(begin))}\" stop=\"${clock.format(Instant.ofEpochMilli(stop))}\" channel=\"${channel.tvgId}\">")
                    append("<title>${titles[(i + row) % titles.size]}</title><desc>Headlines and local stories, with interviews from the studio.</desc></programme>")
                    begin = stop
                }
            }
            append("</tv>")
        }
        val parsed = XmltvParser.parse(xml, start, end) { it.keys }
        channels.associate { it.key to parsed.programs[it.tvgId].orEmpty() }
    }
    private val schedule = guide.mapValues { (_, programs) ->
        NowNext(programs.find { it.isLive(now) }, programs.firstOrNull { it.startMs > now })
    }

    @Test
    fun wideBrowserAndContextMenu() {
        for ((width, height) in wideSizes) {
            var menu by mutableStateOf<LiveChannel?>(null)
            var plays = 0
            scene(width, height) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    NuvioScreenHeader("Live TV", includeStatusBarPadding = false)
                    LiveTvHeaderActions(wide = true, searching = false, showOnDemand = false, sportsOn = true,
                        showRecordings = false, isRecording = false, onSearch = {}, onOpenGuide = {},
                        onOpenMultiview = {}, onOpenCalendar = {}, onOpenOnDemand = {}, onOpenSports = {}, onOpenRecordings = {}, onOpenSettings = {})
                    WideLiveTvBrowser(sections, news, null, setOf(channels.first().key), schedule, now, rememberLazyListState(),
                        onGroup = {}, onGroupActions = {}, onPickFavorites = {}, onPlay = { plays++ }, onChannelActions = { menu = it }, onOpenGuide = {})
                }
                menu?.let { ChannelActionsSheet(it, true, emptyList(), onDismiss = { menu = null }, onPlay = {}, onOpenGuide = {}) }
            }.withScene {
                val channel = text("Metro News")
                listOf("Search", "Guide", "Calendar", "Sports", "Settings").forEach { text(it) }
                assertTrue(channel.boundsInRoot.left > text("News & Local").boundsInRoot.left)
                png("livetv", width, height)
                click(channel.boundsInRoot.center, PointerButton.Secondary)
                png("channel-context", width, height)
                assertEquals(channels.first().key, menu?.key)
                assertEquals(0, plays)
                assertTrue(text("Hide channel").boundsInWindow.width < 540)
            }
        }
    }

    @Test
    fun guideFocusPlaybackAndScroll() {
        val slotWidth = (LiveTvWideGuideHourWidth.value / 2).toInt() // fixture density is 1
        val snappedStart = now - now % LiveTvGuideHalfHourMs
        val snappedOffset = ((snappedStart - start) / LiveTvGuideHalfHourMs).toInt() * slotWidth
        val firstChannel = channels.first()
        val onNow = guide.getValue(firstChannel.key).first { it.isLive(now) }
        val next = guide.getValue(firstChannel.key).first { it.startMs >= onNow.stopMs }
        // A missing schedule and empty space between listings shouldn't move any other cell.
        val listings = guide - channels[5].key
        for ((width, height) in wideSizes) {
            val played = mutableListOf<String>()
            val opened = mutableListOf<Pair<String, EpgProgram>>()
            val contextMenus = mutableListOf<String>()
            val scroll = ScrollState(snappedOffset)
            lateinit var focusManager: FocusManager
            scene(width, height) {
                focusManager = LocalFocusManager.current
                Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    NuvioScreenHeader("TV Guide", includeStatusBarPadding = false)
                    TvGuideFixture(listings, firstChannel, scroll,
                        onPlay = { played += it.key },
                        onProgram = { channel, program -> opened += channel.key to program },
                        onChannelActions = { contextMenus += it.key }, modifier = Modifier.weight(1f),
                        corner = { Text("Sample playlists", color = MaterialTheme.nuvio.colors.textSecondary) })
                }
            }.withScene {
                val title = tagged("desktop-tv-guide-title")
                val first = text(firstChannel.name)
                val cell = text(onNow.title, below = title.boundsInRoot.bottom)
                assertEquals(onNow.title, title.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text)
                text("Headlines and local stories, with interviews from the studio.")
                text("LIVE")
                text("No information")
                assertTrue(text("Sample playlists").boundsInRoot.bottom <= first.boundsInRoot.top)
                assertTrue(title.boundsInRoot.bottom < first.boundsInRoot.top)
                assertTrue(first.boundsInRoot.right < cell.boundsInRoot.left)
                assertTrue(cell.boundsInRoot.width > 40)
                png("guide-tv", width, height)

                val nextCell = text(next.title, below = title.boundsInRoot.bottom)
                sendPointerEvent(PointerEventType.Move, nextCell.boundsInRoot.center, type = PointerType.Mouse)
                renderFrame()
                assertEquals(next.title, tagged("desktop-tv-guide-title").config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text)
                assertTrue(opened.isEmpty(), "Hover previews details without opening a dialog")
                click(cell.boundsInRoot.center)
                assertEquals(listOf(firstChannel.key to onNow), opened)
                // The app's AWT dispatcher calls into Compose spatial focus; ImageComposeScene
                // bypasses that dispatcher. Exercise the same move against the actual tiles here.
                assertTrue(focusManager.moveFocus(FocusDirection.Right))
                renderFrame()
                assertEquals(next.title, tagged("desktop-tv-guide-title").config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text)
                key(Key.Enter)
                key(Key.Enter)
                assertEquals(listOf(firstChannel.key, firstChannel.key), played)
                click(first.boundsInRoot.center, PointerButton.Secondary)
                assertEquals(listOf(firstChannel.key), contextMenus)

                val timeline = Offset(400f, first.boundsInRoot.center.y)
                sendPointerEvent(PointerEventType.Move, timeline, type = PointerType.Mouse)
                sendPointerEvent(PointerEventType.Scroll, timeline, type = PointerType.Mouse, scrollDelta = Offset(3f, 0f))
                renderFrame()
                assertTrue(scroll.value > snappedOffset, "Trackpad/wheel input should move the shared time axis")
                assertEquals(first.boundsInRoot.left, text(firstChannel.name).boundsInRoot.left)
            }
        }
    }

    @Composable
    private fun TvGuideFixture(
        listings: Map<String, List<EpgProgram>>,
        firstChannel: LiveChannel,
        scroll: ScrollState,
        onPlay: (LiveChannel) -> Unit,
        onProgram: (LiveChannel, EpgProgram) -> Unit,
        onChannelActions: (LiveChannel) -> Unit,
        modifier: Modifier,
        corner: @Composable () -> Unit,
    ) {
        val listState = rememberLazyListState()
        val favorites = setOf(firstChannel.key)
        // Exercise the production platform dispatch on macOS, but still test the portable
        // renderer when running desktopTest on another host.
        if (platformLiveTvGuideRenderer != null) {
            LiveTvGuideGrid(channels, listings, favorites, start, end, now, scroll, listState,
                wide = isWideLiveTvWindow(), onPlay = onPlay, onProgram = onProgram, modifier = modifier,
                onChannelActions = onChannelActions, corner = corner)
        } else {
            DesktopTvGuide(channels, listings, favorites, start, end, now, scroll, listState,
                onPlay, onProgram, onChannelActions, modifier, corner)
        }
    }

    @Test
    fun compactGuideUsesSharedLayout() {
        scene(420, 800) {
            Column(Modifier.fillMaxSize()) {
                LiveTvGuideGrid(channels, guide, emptySet(), start, end, now, ScrollState(0),
                    rememberLazyListState(), wide = isWideLiveTvWindow(), onPlay = {}, onProgram = { _, _ -> },
                    modifier = Modifier.weight(1f))
            }
        }.withScene {
            text("Headlines")
            assertTrue(semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
                .none { it.config.getOrNull(SemanticsProperties.TestTag) == "desktop-tv-guide" })
        }
    }

    @Test
    fun sportsGamesReflowAndSearchStaysConstrained() {
        val day = SportsDates.dayStart(0)
        val settings = SportsSettings(region = "US")
        val demoNow = day + 16 * hour
        val games = buildList {
            val teams = listOf("Falcons", "Wolves", "Otters", "Bears", "Foxes", "Hawks", "Ravens", "Stars", "Comets", "Kings", "Jets", "Knights", "Pioneers", "Royals", "Eagles", "Giants")
            repeat(8) { index ->
                val league = if (index < 4) "basketball/nba" else "hockey/nhl"
                fun side(i: Int, home: Boolean): Side {
                    val name = teams[i]
                    val team = TeamInfo(league, "fixture-$i", name, name, name.take(3).uppercase(), null, if (home) 0xFF385C9BL else 0xFF9B4738L, null, null)
                    return Side(team, if (index < 2) if (home) "68" else "61" else null, null, false, home)
                }
                add(SportsEvent("game-$index", league, demoNow + (index - 2) * hour / 2, true,
                    if (index < 2) GameState.LIVE else GameState.PRE, "Fixture game", null, if (index < 2) "3rd · 4:12" else null,
                    side(index * 2, false), side(index * 2 + 1, true)))
            }
        }
        for ((width, height) in wideSizes) {
            scene(width, height) {
                Column(Modifier.fillMaxSize()) {
                    NuvioScreenHeader("Sports", includeStatusBarPadding = false, modifier = Modifier.padding(horizontal = 8.dp))
                    SportsSearchBar("", {}, Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                    AllEventsTab(games, SportsFollows(), settings, emptyMap(), demoNow, false, false,
                        SportsActions({ _, _ -> }, { _, _ -> }, {}, {}), {})
                }
            }.withScene {
                assertTrue(text("Search games, teams, leagues…").boundsInRoot.width < 470)
                text("● LIVE") // A clipped hero rail hid the status row at initial scroll position.
                // The last occurrence is in the league grid, after the featured rail.
                assertTrue(text("Wolves", last = true).boundsInRoot.left < text("Bears", last = true).boundsInRoot.left)
                assertEquals(text("Wolves", last = true).boundsInRoot.top, text("Bears", last = true).boundsInRoot.top, 2f)
                png("sports", width, height)
            }
        }
    }

    @Test
    fun compactLiveTvUsesPhoneLayout() {
        scene(420, 900) {
            val wide = isWideLiveTvWindow()
            assertTrue(!wide)
            // Exercise the real compact list/header and empty state without the process-wide playlist store.
            NuvioScreen {
                stickyHeader {
                    Column(Modifier.background(MaterialTheme.nuvio.colors.background)) {
                        NuvioScreenHeader("Live TV")
                        LiveTvHeaderActions(wide = wide, searching = false, showOnDemand = false, sportsOn = true,
                            showRecordings = false, isRecording = false, onSearch = {}, onOpenGuide = {},
                            onOpenMultiview = {}, onOpenCalendar = {}, onOpenOnDemand = {}, onOpenSports = {}, onOpenRecordings = {}, onOpenSettings = {})
                    }
                }
                item { EmptyLiveTv(onOpenSettings = {}, wide = wide) }
            }
        }.withScene {
            png("livetv-compact", 420, 900)
            assertTrue(text("Add a playlist").boundsInRoot.center.x in 190f..230f)
        }
    }

    @Test
    fun teamPickerReflowsAndTogglesFollows() {
        val leagues = listOf(
            League("fixture/pro", Sport.BASKETBALL, "National Basketball League", "NBL"),
            League("fixture/college", Sport.BASKETBALL, "College Basketball", "College"),
            League("fixture/hockey", Sport.HOCKEY, "National Hockey League", "NHL"),
            League("fixture/soccer", Sport.FOOTBALL, "Premier League", "EPL"),
            League("fixture/baseball", Sport.BASEBALL, "Major League Baseball", "MLB"),
            League("fixture/rugby", Sport.RUGBY, "Super Rugby", "SR"),
        )
        val teamNames = listOf(
            "Boston Harbors", "Brooklyn Towers", "Chicago Flyers", "Cleveland Captains", "Dallas Outlaws",
            "Denver Summits", "Detroit Foundry", "Golden State Tides", "Houston Comets", "Indiana Racers",
            "Los Angeles Stars", "Memphis Kings", "Miami Palms", "Milwaukee Herd", "Minnesota North Stars",
            "New York Knights", "Oklahoma City Cyclones", "Orlando Suns", "Philadelphia Liberty", "Phoenix Firebirds",
            "Portland Pioneers", "Sacramento Gold", "San Antonio Arrows", "Toronto Owls", "Utah Peaks", "Washington Generals",
        )
        val colors = listOf(0xFF177149L, 0xFF71539BL, 0xFF9B353FL, 0xFF2A5EADL, 0xFF9C4C35L, 0xFF286A8EL)
        val proTeams = teamNames.mapIndexed { i, name ->
            TeamInfo(leagues[0].id, "team-$i", name, name, name.take(3).uppercase(), null,
                colors[i % colors.size], null, null, "${20 + i}–${10 + i}")
        }
        val collegeTeam = TeamInfo(leagues[1].id, "college", "Riverside Owls", "Riverside Owls", "RIV", null, colors[2], null, null)
        val hockeyTeam = TeamInfo(leagues[2].id, "hockey", "Halifax Icebreakers", "Halifax Icebreakers", "HAL", null, colors[3], null, null)
        fun TeamInfo.asFollow() = FollowedTeam(leagueId, id, name, abbr, color, logo)
        for ((width, height) in listOf(1280 to 800, 420 to 900)) {
            var follows by mutableStateOf(SportsFollows(teams = listOf(proTeams[0].asFollow(), proTeams[5].asFollow())))
            var open by mutableStateOf(true)
            scene(width, height) {
                if (open) PickYourTeamsSheet(leagues, follows, teamsFor = { league ->
                    when (league) {
                        leagues[0] -> proTeams
                        leagues[1] -> listOf(collegeTeam)
                        leagues[2] -> listOf(hockeyTeam)
                        else -> emptyList()
                    }
                }, onToggle = { team ->
                    val old = follows.followedTeam(team)
                    follows = follows.copy(teams = if (old == null) follows.teams + team.asFollow() else follows.teams - old)
                }, onDismiss = { open = false })
            }.withScene {
                text("Pick your teams")
                text("Search teams across all leagues")
                val first = text(proTeams[0].fullName)
                val second = text(proTeams[1].fullName)
                val sport = text("🏀  Basketball")
                val league = text(leagues[0].name)
                if (width == 1280) {
                    assertTrue(sport.boundsInWindow.right < league.boundsInWindow.left)
                    assertTrue(first.boundsInWindow.right <= second.boundsInWindow.left)
                    assertEquals(first.boundsInWindow.top, second.boundsInWindow.top)
                } else {
                    assertTrue(sport.boundsInWindow.bottom <= league.boundsInWindow.top)
                    assertTrue(first.boundsInWindow.bottom <= second.boundsInWindow.top)
                }
                assertTrue(league.boundsInWindow.bottom <= first.boundsInWindow.top)
                png("sports-picker", width, height)
                click(second.boundsInWindow.center)
                assertNotNull(follows.followedTeam(proTeams[1]))
                val toggle = generateSequence(text(proTeams[1].fullName)) { it.parent }
                    .firstNotNullOfOrNull { it.config.getOrNull(SemanticsProperties.ToggleableState) }
                assertEquals(ToggleableState.On, toggle)
                click(second.boundsInWindow.center)
                assertEquals(null, follows.followedTeam(proTeams[1]))
                click(text(leagues[1].name).boundsInWindow.center)
                text(collegeTeam.fullName)
                click(text("🏒  Hockey").boundsInWindow.center)
                text(hockeyTeam.fullName)
                click(text("Done").boundsInWindow.center)
                assertEquals(false, open)
            }
        }
    }

    private fun scene(width: Int, height: Int, content: @Composable () -> Unit): ImageComposeScene {
        val window = object : WindowInfo {
            override val isWindowFocused = true
            override val containerSize = IntSize(width, height)
            override val containerDpSize = DpSize(width.dp, height.dp)
        }
        return ImageComposeScene(width, height, Density(1f)) {
            CompositionLocalProvider(LocalWindowInfo provides window, LocalScreenActive provides false) {
                NuvioTheme(darkTheme = true) {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.nuvio.colors.background)) { content() }
                }
            }
        }
    }

    private fun ImageComposeScene.withScene(block: ImageComposeScene.() -> Unit) {
        try {
            renderFrame()
            block()
        } finally {
            close()
        }
    }

    private var frameTime = System.nanoTime()

    private fun ImageComposeScene.renderFrame() {
        repeat(8) {
            frameTime += 32_000_000L
            render(frameTime).close()
        }
    }

    private fun ImageComposeScene.png(name: String, width: Int, height: Int) {
        renderFrame()
        val dir = output ?: return
        Files.createDirectories(dir)
        render(frameTime).use { image ->
            assertEquals(width, image.width)
            assertEquals(height, image.height)
            assertNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { data ->
                Files.write(dir.resolve("$name-${width}x$height.png"), data.bytes)
            }
        }
    }

    private fun ImageComposeScene.tagged(tag: String): SemanticsNode = assertNotNull(
        semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
            .firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag },
        "Visible node: $tag",
    )

    private fun ImageComposeScene.text(value: String, last: Boolean = false, below: Float = 0f): SemanticsNode {
        val matches = semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
            .filter { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true &&
                node.boundsInRoot.width > 0 && node.boundsInRoot.top >= below }
        return assertNotNull(if (last) matches.maxByOrNull { it.boundsInRoot.top } else matches.firstOrNull(), "Visible text: $value")
    }

    private fun ImageComposeScene.click(position: Offset, button: PointerButton = PointerButton.Primary) {
        sendPointerEvent(PointerEventType.Move, position, type = PointerType.Mouse)
        sendPointerEvent(PointerEventType.Press, position, type = PointerType.Mouse, button = button,
            buttons = PointerButtons(isPrimaryPressed = button == PointerButton.Primary, isSecondaryPressed = button == PointerButton.Secondary))
        sendPointerEvent(PointerEventType.Release, position, type = PointerType.Mouse, button = button)
        renderFrame()
    }

    private fun ImageComposeScene.key(key: Key) {
        sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown))
        sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp))
        renderFrame()
    }

    private val wideSizes = listOf(1280 to 800, 1920 to 1080)
}
