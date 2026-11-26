package com.nuvio.app.features.player

import android.content.Context
import android.util.Log
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import com.nuvio.app.features.livetv.player.LiveCaptions
import com.nuvio.app.features.livetv.player.continuousTsTransport

/** Preserve the normal TS track mode; live transport length is handled separately below. */
@UnstableApi
internal fun playerExtractorsFactory(liveCaptions: Boolean): ExtractorsFactory {
    val flags = DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS or
        (if (liveCaptions) DefaultTsPayloadReaderFactory.FLAG_OVERRIDE_CAPTION_DESCRIPTORS else 0)
    val factory = DefaultExtractorsFactory().setTsExtractorFlags(flags)
        .setTsExtractorTimestampSearchBytes(1500 * TsExtractor.TS_PACKET_SIZE)
    return if (liveCaptions) LiveCaptions.Extractors(factory.setTsSubtitleFormats(LiveCaptions.formats)) else factory
}

/** Build the fullscreen media path without caching a continuous channel as a finite file. */
@UnstableApi
internal fun playerMediaDataSourceFactory(
    context: Context,
    upstream: DataSource.Factory,
    sourceUrl: String,
    mimeType: String?,
    continuousLive: Boolean = false,
    vodCacheEnabled: Boolean,
    youtubeChunked: Boolean,
    loopback: Boolean,
    stalker: Boolean,
    sizeMode: VodCacheSizeMode,
    sizeMb: Int,
): DataSource.Factory {
    val adaptive = mimeType == MimeTypes.APPLICATION_M3U8 ||
        mimeType == MimeTypes.APPLICATION_MPD || mimeType == MimeTypes.APPLICATION_SS
    if (loopback && vodCacheEnabled && !youtubeChunked && !adaptive) {
        Log.i("NuvioPlayer", "Skipping VOD cache for loopback playback")
    }
    val media = if (stalker || !useVodCacheForSource(
            vodCacheEnabled = vodCacheEnabled,
            youtubeChunked = youtubeChunked,
            adaptive = adaptive,
            loopback = loopback,
            continuousLive = continuousLive,
        )) {
        // The upstream DefaultDataSource also handles data: initialization segments.
        // Stalker links can be single-use, so leave their request behavior unchanged.
        if (mimeType == MimeTypes.APPLICATION_M3U8 && !stalker) HlsParameterSetInitDataSource.Factory(upstream) else upstream
    } else {
        VodCache.wrap(context, upstream, sourceUrl, enabled = true, sizeMode = sizeMode, sizeMb = sizeMb)
    }
    return continuousTsTransport(media, sourceUrl, mimeType, continuousLive)
}
