package com.nuvio.vpn.android

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaExtractor
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.extractor.DummyTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.livetv.data.LiveTvPreferences
import com.nuvio.app.features.livetv.model.CatchupInfo
import com.nuvio.app.features.livetv.model.EpgProgram
import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.model.LiveTvClock
import com.nuvio.app.features.livetv.multiview.multiviewMediaSource
import com.nuvio.app.features.livetv.player.ContinuousTsDataSourceFactory
import com.nuvio.app.features.livetv.player.LivePlayRequest
import com.nuvio.app.features.livetv.player.LiveTvPlayback
import com.nuvio.app.features.livetv.player.continuousTsTransport
import com.nuvio.app.features.livetv.sports.SportsAddonSource
import com.nuvio.app.features.livetv.sports.SportsVideos
import com.nuvio.app.features.player.PlatformPlaybackDataSourceFactory
import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.player.PlayerLaunchStore
import com.nuvio.app.features.player.VodCacheSizeMode
import com.nuvio.app.features.player.playbackMediaItemFromUrl
import com.nuvio.app.features.player.playerExtractorsFactory
import com.nuvio.app.features.player.playerMediaDataSourceFactory
import com.nuvio.app.features.player.useVodCacheForSource
import com.nuvio.vpn.core.VpnPreferences
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Packaged source factories, protected local HTTP transport and the actual Media3 TS extractor. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@androidx.annotation.OptIn(UnstableApi::class)
class ContinuousLiveTsTransportTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun localSettingsOnly() {
        // The host-test application has its own sandbox. No profile or tunnel is imported.
        VpnPlaybackProtection.initialize(context)
        VpnPlaybackProtection.setActiveUserProfile(1)
        AndroidVpnSettings(context).save(VpnPreferences.defaults())
        VpnPlaybackProtection.settings()
    }

    @Test fun fullScreenLiveTsUsesOneOpenWithoutDroppingEitherAudioTrack() {
        for (captions in listOf(false, true)) server { server, requests ->
            val launch = launch(channel(server.url("/live/fixture-user/fixture-password/42.ts").toString()))
            val result = extract(fullScreenSource(launch, captions))
            assertEquals(C.LENGTH_UNSET.toLong(), result.length)
            assertEquals(C.TIME_UNSET, result.durationUs)
            assertEquals(2, result.audioTracks)
            assertEquals(listOf<String?>(null), requests.map { it.getHeader("Range") })
            assertEquals("synthetic-player", requests.single().getHeader("User-Agent"))
            assertEquals("one", requests.single().getHeader("X-Test-Stream"))
        }
    }

    @Test fun fullScreenMoviesCatchupRecordingsAndSportsKeepFiniteTsSemantics() = server { server, requests ->
        fun checkFinite(launch: PlayerLaunch) {
            val start = requests.size
            val result = extract(fullScreenSource(launch))
            assertEquals(fixture.size.toLong(), result.length)
            assertTrue(result.durationUs > 3_000_000L)
            assertEquals(2, result.audioTracks)
            assertEquals(listOf(null, "bytes=${fixture.size - TS_SEARCH_BYTES}-", null),
                requests.drop(start).map { it.getHeader("Range") })
        }
        val movie = launch(channel(server.url("/live/fixture-user/fixture-password/42.ts").toString()))
            .copy(sourceUrl = server.url("/movie/fixture-user/fixture-password/43.ts").toString(), providerAddonId = "movie-addon")
        checkFinite(movie)
        checkFinite(launch(channel(server.url("/sports/replay.ts").toString(), SportsAddonSource.SOURCE_ID)))
        checkFinite(launch(channel(server.url("/sports/highlight.ts").toString(), SportsVideos.SOURCE_ID)))
        val recording = launch(channel(server.url("/recording/saved.ts").toString()))
        LiveTvPlayback.noteRecording(recording.sourceUrl)
        checkFinite(recording)
        withCatchup(server) { _, catchup -> checkFinite(catchup) }
    }

    @Test fun actualMultiviewFactoryUsesContinuousTransportOnlyForLiveChannels() = server { server, requests ->
        fun check(selected: LiveChannel, request: LivePlayRequest, continuous: Boolean) {
            val before = requests.size
            val source = multiviewMediaSource(context, selected, request) as ProgressiveMediaSource
            val result = extract(source)
            assertEquals(if (continuous) C.LENGTH_UNSET.toLong() else fixture.size.toLong(), result.length)
            if (continuous) assertEquals(C.TIME_UNSET, result.durationUs) else assertTrue(result.durationUs > 3_000_000L)
            assertEquals(2, result.audioTracks)
            assertEquals(if (continuous) 1 else 3, requests.size - before)
        }
        val live = channel(server.url("/live/fixture-user/fixture-password/44.ts").toString())
        check(live, runBlocking { LiveTvPlayback.requestFor(live) }, true)
        for (sourceId in listOf(SportsAddonSource.SOURCE_ID, SportsVideos.SOURCE_ID)) {
            val replay = channel(server.url("/sports/$sourceId.ts").toString(), sourceId)
            check(replay, runBlocking { LiveTvPlayback.requestFor(replay) }, false)
        }
        val recording = channel(server.url("/recording/tile.ts").toString())
        LiveTvPlayback.noteRecording(recording.url)
        check(recording, runBlocking { LiveTvPlayback.requestFor(recording) }, false)
        withCatchup(server) { selected, catchup ->
            check(selected, LivePlayRequest(catchup.sourceUrl, catchup.sourceHeaders, catchup.streamType), false)
        }
        val hls = channel(server.url("/live/index.m3u8").toString())
        assertTrue(multiviewMediaSource(context, hls, runBlocking { LiveTvPlayback.requestFor(hls) }) is HlsMediaSource)
    }

    @Test fun actualLiveMediaPeriodPublishesAnUnknownLengthLiveWindow() {
        for (multiview in listOf(false, true)) server(throttled = true) { server, requests ->
            val selected = channel(server.url("/live/fixture-user/fixture-password/45.ts").toString())
            val source = if (multiview) multiviewMediaSource(context, selected, runBlocking { LiveTvPlayback.requestFor(selected) })
                else fullScreenSource(launch(selected))
            var timeline: Timeline? = null
            val caller = MediaSource.MediaSourceCaller { _, updated -> timeline = updated }
            source.prepareSource(caller, null, PlayerId.UNSET)
            val initial = assertNotNull(timeline)
            val period = source.createPeriod(MediaSource.MediaPeriodId(initial.getUidOfPeriod(0)), DefaultAllocator(true, 64 * 1024), 0)
            var prepared = false
            try {
                period.prepare(object : MediaPeriod.Callback {
                    override fun onPrepared(mediaPeriod: MediaPeriod) { prepared = true }
                    override fun onContinueLoadingRequested(source: MediaPeriod) {
                        source.continueLoading(LoadingInfo.Builder().setPlaybackPositionUs(0).build())
                    }
                }, 0)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!prepared && System.nanoTime() < deadline) {
                    shadowOf(Looper.getMainLooper()).idle()
                    period.maybeThrowPrepareError()
                    Thread.sleep(2)
                }
                assertTrue(prepared, "Actual media period did not prepare")
                val window = assertNotNull(timeline).getWindow(0, Timeline.Window())
                assertFalse(window.isPlaceholder)
                assertTrue(window.isLive)
                assertEquals(C.TIME_UNSET, window.durationUs)
                assertEquals(1, requests.size)
            } finally {
                source.releasePeriod(period)
                source.releaseSource(caller)
                shadowOf(Looper.getMainLooper()).idle()
            }
        }
    }

    @Test fun onlyExplicitHttpTsIntentChangesTheOpenLength() {
        val upstream = RecordingDataSource()
        val factory = DataSource.Factory { upstream }
        for ((url, mime, live, expectedUnknown) in listOf(
            Scope("https://example.test/live.ts", MimeTypes.VIDEO_MP2T, true, true),
            Scope("http://example.test/extensionless", MimeTypes.VIDEO_MP2T, true, true),
            Scope("https://example.test/extensionless", null, true, false),
            Scope("https://example.test/replay.ts", MimeTypes.VIDEO_MP2T, false, false),
            Scope("https://example.test/movie.mp4", MimeTypes.VIDEO_MP4, true, false),
            Scope("https://example.test/index.m3u8", MimeTypes.APPLICATION_M3U8, true, false),
            Scope("file:///recording.ts", MimeTypes.VIDEO_MP2T, true, false),
            Scope("content://recording/45.ts", MimeTypes.VIDEO_MP2T, true, false),
        )) {
            val adapted = continuousTsTransport(factory, url, mime, live)
            if (!expectedUnknown) assertSame(factory, adapted)
            val source = adapted.createDataSource()
            try {
                assertEquals(if (expectedUnknown) C.LENGTH_UNSET.toLong() else 1234L, source.open(DataSpec(Uri.parse(url))), url)
            } finally { source.close() }
        }
    }

    @Test fun adapterPreservesTheRequestReadsMetadataListenersCloseAndFailures() {
        val upstream = RecordingDataSource()
        val source = ContinuousTsDataSourceFactory(DataSource.Factory { upstream }).createDataSource()
        val request = DataSpec.Builder().setUri("https://example.test/live.ts").setPosition(188)
            .setHttpRequestHeaders(mapOf("X-Test" to "value")).build()
        val listener: TransferListener = object : TransferListener {
            override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
            override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
            override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) = Unit
            override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        }
        source.addTransferListener(listener)
        assertEquals(C.LENGTH_UNSET.toLong(), source.open(request))
        assertSame(request, upstream.request)
        assertEquals(listOf(listener), upstream.listeners)
        val bytes = ByteArray(8)
        assertEquals(4, source.read(bytes, 1, 4))
        upstream.ended = true
        assertEquals(C.RESULT_END_OF_INPUT, source.read(bytes, 0, 8))
        assertEquals(upstream.getUri(), source.uri)
        assertSame(upstream.getResponseHeaders(), source.responseHeaders)
        val readFailure = IOException("Synthetic read failure")
        upstream.readFailure = readFailure
        assertSame(readFailure, runCatching { source.read(bytes, 0, 8) }.exceptionOrNull())
        source.close()
        assertEquals(1, upstream.closed)
        val openFailure = IOException("Synthetic open failure")
        upstream.openFailure = openFailure
        assertSame(openFailure, runCatching { source.open(request) }.exceptionOrNull())
    }

    @Test fun enabledVodCacheIsBypassedForCurrentContinuousLiveWithoutChangingVodDefaults() {
        val url = "https://example.test/live/fixture-user/fixture-password/46.ts"
        val current = launch(channel(url))
        val cacheForbidden = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = error("Continuous live media must not initialize VOD cache storage")
        }
        val media = playerMediaDataSourceFactory(cacheForbidden, DataSource.Factory { RecordingDataSource() }, url,
            MimeTypes.VIDEO_MP2T, continuousLive = LiveTvPlayback.isContinuousLive(current.providerAddonId, url),
            vodCacheEnabled = true, youtubeChunked = false, loopback = false,
            stalker = false, sizeMode = VodCacheSizeMode.AUTO, sizeMb = 500)
        val source = media.createDataSource()
        try { assertEquals(C.LENGTH_UNSET.toLong(), source.open(DataSpec(Uri.parse(url)))) }
        finally { source.close() }
        assertTrue(useVodCacheForSource(true, false, false, false))
        assertTrue(useVodCacheForSource(true, false, false, false, continuousLive = false))
        assertFalse(useVodCacheForSource(true, false, false, false, continuousLive = true))
        assertFalse(useVodCacheForSource(false, false, false, false))
    }

    @Test fun sameUrlOpenedByAMovieProviderKeepsItsFiniteDurationAndVodCacheEligibility() = server { server, requests ->
        val url = server.url("/live/fixture-user/fixture-password/48.ts").toString()
        val live = launch(channel(url))
        assertEquals(C.LENGTH_UNSET.toLong(), extract(fullScreenSource(live)).length)
        assertEquals(1, requests.size)

        // The URL remains in the live registry, but this new launch belongs to a movie provider.
        val movie = live.copy(providerAddonId = "movie-addon", contentType = "movie")
        assertTrue(LiveTvPlayback.isContinuousLive(live.providerAddonId, url))
        val currentIntent = LiveTvPlayback.isContinuousLive(movie.providerAddonId, url)
        assertFalse(currentIntent)
        val finite = extract(fullScreenSource(movie))
        assertEquals(fixture.size.toLong(), finite.length)
        assertTrue(finite.durationUs > 3_000_000L)
        assertEquals(2, finite.audioTracks)
        assertEquals(listOf(null, "bytes=${fixture.size - TS_SEARCH_BYTES}-", null),
            requests.drop(1).map { it.getHeader("Range") })

        val cacheAccess = IllegalStateException("Synthetic VOD cache access")
        val cacheContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = throw cacheAccess
        }
        // Reaching VOD's existing storage path proves the prior live URL did not bypass its cache.
        assertSame(cacheAccess, runCatching {
            playerMediaDataSourceFactory(cacheContext, DataSource.Factory { RecordingDataSource() }, url,
                MimeTypes.VIDEO_MP2T, continuousLive = currentIntent, vodCacheEnabled = true,
                youtubeChunked = false, loopback = false, stalker = false,
                sizeMode = VodCacheSizeMode.AUTO, sizeMb = 500)
        }.exceptionOrNull())
    }

    private fun fullScreenSource(launch: PlayerLaunch, captions: Boolean = true): ProgressiveMediaSource {
        val item = playbackMediaItemFromUrl(launch.sourceUrl, streamType = launch.streamType)
        val upstream = PlatformPlaybackDataSourceFactory.create(context, launch.sourceHeaders, emptyMap(), useYoutubeChunkedPlayback = false)
        val media = playerMediaDataSourceFactory(context, upstream, launch.sourceUrl, item.localConfiguration?.mimeType,
            continuousLive = LiveTvPlayback.isContinuousLive(launch.providerAddonId, launch.sourceUrl),
            vodCacheEnabled = false, youtubeChunked = false, loopback = false, stalker = false,
            sizeMode = VodCacheSizeMode.AUTO, sizeMb = 500)
        return DefaultMediaSourceFactory(media, playerExtractorsFactory(captions)).createMediaSource(item) as ProgressiveMediaSource
    }

    private fun withCatchup(server: MockWebServer, block: (LiveChannel, PlayerLaunch) -> Unit) {
        val previous = LiveTvPreferences.rawData.value
        try {
            LiveTvPreferences.edit { it["catchup_prefer_hls"] = com.nuvio.app.features.livetv.data.PrefValue.Bool(false) }
            val selected = channel(server.url("/live/fixture-user/fixture-password/47.ts").toString()).copy(catchup = CatchupInfo("xc", null, 3))
            val now = LiveTvClock.nowMs()
            val id = assertNotNull(runBlocking { LiveTvPlayback.prepareCatchup(selected, EpgProgram(now - 7_200_000L, now - 3_600_000L, "Past show"), 1) })
            try {
                val launch = assertNotNull(PlayerLaunchStore.get(id))
                assertTrue(LiveTvPlayback.isCatchup(launch.sourceUrl))
                block(selected, launch)
            } finally { PlayerLaunchStore.remove(id) }
        } finally {
            LiveTvPreferences.edit { it.clear(); it.putAll(previous) }
            NuvioToastController.dismiss()
        }
    }

    private fun launch(channel: LiveChannel): PlayerLaunch = runBlocking { LiveTvPlayback.launchFor(channel, 1) }

    private fun channel(url: String, sourceId: String = "synthetic-xtream") = LiveChannel(
        key = "$sourceId:$url", sourceId = sourceId, sourceName = "Synthetic", name = "Synthetic channel",
        tvgId = null, tvgName = null, logo = null, groupId = "group", group = "Group", number = 1, url = url,
        headers = mapOf("User-Agent" to "synthetic-player", "X-Test-Stream" to "one"),
    )

    private data class Scope(val url: String, val mime: String?, val live: Boolean, val unknown: Boolean)
    private data class Extraction(val length: Long, val durationUs: Long, val audioTracks: Int)

    private fun extract(source: ProgressiveMediaSource): Extraction {
        val transport = (field(source, "dataSourceFactory") as DataSource.Factory).createDataSource()
        val extractor = (field(source, "progressiveMediaExtractorFactory") as ProgressiveMediaExtractor.Factory)
            .createProgressiveMediaExtractor(PlayerId.UNSET)
        val audio = HashSet<Int>()
        var duration = C.TIME_UNSET
        val output = object : ExtractorOutput {
            override fun track(id: Int, type: Int) = DummyTrackOutput().also { if (type == C.TRACK_TYPE_AUDIO) audio += id }
            override fun endTracks() = Unit
            override fun seekMap(seekMap: SeekMap) { duration = seekMap.durationUs }
        }
        val seek = PositionHolder()
        var at = 0L
        var firstLength = C.LENGTH_UNSET.toLong()
        try {
            repeat(12) { open ->
                val length = transport.open(DataSpec.Builder().setUri(source.mediaItem.localConfiguration!!.uri).setPosition(at).build())
                if (open == 0) firstLength = length
                extractor.init(transport, assertNotNull(transport.uri), transport.responseHeaders, at,
                    if (length == C.LENGTH_UNSET.toLong()) length else at + length, output)
                var result = Extractor.RESULT_CONTINUE
                for (read in 0 until 100_000) {
                    result = extractor.read(seek)
                    if (result != Extractor.RESULT_CONTINUE) break
                }
                if (result == Extractor.RESULT_END_OF_INPUT) return Extraction(firstLength, duration, audio.size)
                check(result == Extractor.RESULT_SEEK) { "Extractor did not finish" }
                at = seek.position
                transport.close()
            }
            error("Too many extractor opens")
        } finally { transport.close(); extractor.release() }
    }

    private fun field(owner: Any, name: String): Any = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)

    private fun server(throttled: Boolean = false, block: (MockWebServer, List<RecordedRequest>) -> Unit) {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    val start = request.getHeader("Range")?.substringAfter("bytes=")?.substringBefore('-')?.toInt() ?: 0
                    return MockResponse().setResponseCode(if (start == 0) 200 else 206).setHeader("Content-Type", "video/mp2t")
                        .setBody(Buffer().write(fixture, start, fixture.size - start)).apply {
                            if (start > 0) setHeader("Content-Range", "bytes $start-${fixture.lastIndex}/${fixture.size}")
                            if (throttled) throttleBody(4096, 10, TimeUnit.MILLISECONDS)
                        }
                }
            }
            server.start()
            block(server, requests)
        }
    }

    private class RecordingDataSource : DataSource {
        var request: DataSpec? = null
        val listeners = mutableListOf<TransferListener>()
        var ended = false
        var closed = 0
        var openFailure: IOException? = null
        var readFailure: IOException? = null
        private val response = mapOf("Content-Length" to listOf("1234"))
        override fun addTransferListener(transferListener: TransferListener) { listeners += transferListener }
        override fun open(dataSpec: DataSpec): Long {
            openFailure?.let { throw it }
            request = dataSpec
            return 1234L
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            readFailure?.let { throw it }
            return if (ended) C.RESULT_END_OF_INPUT else length
        }
        override fun getUri(): Uri = Uri.parse("https://example.test/redirected.ts")
        override fun getResponseHeaders(): Map<String, List<String>> = response
        override fun close() { closed++ }
    }

    companion object {
        private const val TS_SEARCH_BYTES = 1500 * 188
        // Generated four-second black H264 clip, two silent AAC tracks and TS padding.
        // Uncompressed SHA256: 4ce837aa97873e34b37927e3039178d89c258a985999d2fd89a0dd065528fbcc.
        private val fixture by lazy {
            GZIPInputStream(assertNotNull(ContinuousLiveTsTransportTest::class.java.getResourceAsStream("/livetv/continuous-multi-audio.ts.gz")))
                .use { it.readBytes() }
        }
    }
}
