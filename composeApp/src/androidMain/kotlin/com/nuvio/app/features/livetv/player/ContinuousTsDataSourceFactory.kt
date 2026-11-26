package com.nuvio.app.features.livetv.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec

/** A continuous channel has no file tail for the progressive TS duration probe to seek to. */
@UnstableApi
internal class ContinuousTsDataSourceFactory(private val upstream: DataSource.Factory) : DataSource.Factory {
    override fun createDataSource(): DataSource = ContinuousTsDataSource(upstream.createDataSource())

    private class ContinuousTsDataSource(private val upstream: DataSource) : DataSource by upstream {
        // Kotlin delegation does not forward this Java interface's default implementation.
        override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

        override fun open(dataSpec: DataSpec): Long {
            upstream.open(dataSpec)
            // Keep HTTP requests, headers, reads and real EOF/errors unchanged. Only the loader's
            // file-length estimate is unknown, so TsExtractor does not reopen the channel at its tail.
            return C.LENGTH_UNSET.toLong()
        }
    }
}

/** Explicit live intent is required: a TS extension alone also describes recordings and replays. */
@UnstableApi
internal fun continuousTsTransport(
    upstream: DataSource.Factory,
    url: String,
    mimeType: String?,
    continuousLive: Boolean,
): DataSource.Factory {
    val scheme = Uri.parse(url).scheme
    return if (continuousLive && mimeType == MimeTypes.VIDEO_MP2T &&
        (scheme.equals("http", true) || scheme.equals("https", true))) {
        ContinuousTsDataSourceFactory(upstream)
    } else upstream
}
