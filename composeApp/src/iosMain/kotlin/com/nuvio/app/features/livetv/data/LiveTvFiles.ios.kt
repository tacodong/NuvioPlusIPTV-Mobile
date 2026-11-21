package com.nuvio.app.features.livetv.data

import com.nuvio.app.features.livetv.model.PlaylistSource
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSDate
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSUUID
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIDevice
import platform.posix.chmod
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.rename
import platform.zlib.Z_OK
import platform.zlib.gzFile
import platform.zlib.gzclose
import platform.zlib.gzopen
import platform.zlib.gzread
import platform.zlib.gzwrite

@OptIn(ExperimentalForeignApi::class)
internal actual object LiveTvFiles {
    private const val chunkSize = 128 * 1024
    private val manager get() = NSFileManager.defaultManager
    private val client by lazy { createXtreamHttpClient() }

    internal fun directory(dir: String): String {
        require(dir in setOf(LiveTvDirs.PLAYLISTS, LiveTvDirs.EPG, LiveTvDirs.STATE,
            LiveTvDirs.SPORTS, LiveTvDirs.LOGOS, LiveTvDirs.CALENDAR))
        val path = "${NSHomeDirectory()}/Library/Application Support/Nuvio/LiveTV/$dir"
        if (!manager.fileExistsAtPath(path)) {
            check(manager.createDirectoryAtPath(path, true, null, null)) { "Could not create Live TV storage." }
            check(chmod(path, 448u) == 0) { "Could not protect Live TV storage." } // 0700
        }
        return path
    }

    private fun location(dir: String, name: String): String {
        require(name.isNotEmpty() && name != "." && name != ".." && name.none { it == '/' || it == '\u0000' })
        return "${directory(dir)}/$name"
    }

    private fun stage(target: String): String = "$target.${NSUUID().UUIDString}.tmp"

    actual fun exists(dir: String, name: String): Boolean = manager.fileExistsAtPath(location(dir, name))

    actual fun lastModified(dir: String, name: String): Long =
        ((manager.attributesOfItemAtPath(location(dir, name), null)?.get(NSFileModificationDate) as? NSDate)
            ?.timeIntervalSince1970?.times(1000.0))?.toLong() ?: 0L

    actual fun size(dir: String, name: String): Long =
        (manager.attributesOfItemAtPath(location(dir, name), null)?.get(NSFileSize) as? Number)?.toLong() ?: 0L

    actual fun delete(dir: String, name: String) {
        manager.removeItemAtPath(location(dir, name), null)
    }

    actual fun list(dir: String): List<String> =
        manager.contentsOfDirectoryAtPath(directory(dir), null)?.filterIsInstance<String>()
            ?.filterNot { it.endsWith(".tmp") }.orEmpty()

    private fun protect(path: String) {
        check(chmod(path, 384u) == 0) { "Could not protect the Live TV file." } // 0600
    }

    private fun publish(staged: String, target: String): Boolean {
        if (existsAndMatches(target, staged)) return false
        check(rename(staged, target) == 0) { "Could not publish the Live TV file." }
        return true
    }

    actual suspend fun download(
        url: String, dir: String, name: String, userAgent: String, checkPlaylist: Boolean,
        onProgress: ((bytes: Long) -> Unit)?,
    ): Boolean = withContext(Dispatchers.Default) {
        val target = location(dir, name)
        val staged = stage(target)
        try {
            client.prepareGet(PlaylistSource.tidyLink(url)) {
                if (userAgent.isNotBlank()) header(HttpHeaders.UserAgent, userAgent)
                if (checkPlaylist) header(HttpHeaders.Accept, "*/*")
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    val message = "HTTP ${response.status.value}"
                    if (checkPlaylist && response.status.value in setOf(403, 406)) {
                        throw NotAPlaylistException(message)
                    }
                    error(message)
                }
                val body = response.bodyAsChannel()
                val file = checkNotNull(fopen(staged, "wb")) { "Could not save the Live TV download." }
                var written = 0L
                try {
                    protect(staged)
                    val buffer = ByteArray(chunkSize)
                    while (true) {
                        val count = body.readAvailable(buffer, 0, buffer.size)
                        if (count < 0) break
                        if (count == 0) continue
                        val saved = buffer.usePinned { fwrite(it.addressOf(0), 1uL, count.toULong(), file) }
                        check(saved == count.toULong()) { "Could not finish saving the Live TV download." }
                        written += count
                        onProgress?.invoke(written)
                    }
                } finally {
                    fclose(file)
                }
                check(written > 0L) { "The server returned an empty file." }
            }
            if (checkPlaylist) {
                val first = readFile(staged) { it.firstOrNull().orEmpty() }.orEmpty()
                    .trimStart('\uFEFF', ' ', '\r', '\n', '\t')
                if (!first.startsWith("#EXTM3U", ignoreCase = true) &&
                    !first.startsWith("#EXTINF", ignoreCase = true)) {
                    throw NotAPlaylistException("content that was not an M3U playlist")
                }
            }
            publish(staged, target)
        } finally {
            manager.removeItemAtPath(staged, null)
        }
    }

    private fun writeGzip(file: gzFile, text: String) {
        val bytes = text.encodeToByteArray()
        if (bytes.isEmpty()) return
        val count = bytes.usePinned { gzwrite(file, it.addressOf(0), bytes.size.toUInt()) }
        check(count == bytes.size) { "Could not save the Live TV file." }
    }

    actual fun writeText(dir: String, name: String, text: String): Boolean {
        val target = location(dir, name)
        val staged = stage(target)
        try {
            val file = checkNotNull(gzopen(staged, "wb1")) { "Could not open the Live TV file." }
            try {
                protect(staged)
                writeGzip(file, text)
            } finally {
                check(gzclose(file) == Z_OK) { "Could not finish the Live TV file." }
            }
            return publish(staged, target)
        } finally {
            manager.removeItemAtPath(staged, null)
        }
    }

    actual suspend fun writeTextStream(
        dir: String, name: String, publish: (() -> Unit) -> Unit,
        producer: suspend ((String) -> Unit) -> Unit,
    ): Boolean = withContext(Dispatchers.Default) {
        val target = location(dir, name)
        val staged = stage(target)
        try {
            val file = checkNotNull(gzopen(staged, "wb1")) { "Could not open the Live TV file." }
            try {
                protect(staged)
                producer { writeGzip(file, it) }
            } finally {
                check(gzclose(file) == Z_OK) { "Could not finish the Live TV file." }
            }
            val changed = !existsAndMatches(target, staged)
            var published = false
            publish {
                if (changed) check(rename(staged, target) == 0) { "Could not publish the Live TV file." }
                published = true
            }
            published && changed
        } finally {
            manager.removeItemAtPath(staged, null)
        }
    }

    actual fun <T> readChunks(dir: String, name: String, block: (Sequence<String>) -> T): T? =
        readFile(location(dir, name), block)

    private fun <T> readFile(path: String, block: (Sequence<String>) -> T): T? {
        val file = gzopen(path, "rb") ?: return null
        try {
            return block(sequence {
                val bytes = ByteArray(chunkSize + 4)
                var carry = 0
                while (true) {
                    val count = bytes.usePinned { gzread(file, it.addressOf(carry).reinterpret(), chunkSize.toUInt()) }
                    check(count >= 0) { "Could not read the Live TV file." }
                    if (count == 0) {
                        if (carry > 0) yield(bytes.decodeToString(endIndex = carry))
                        break
                    }
                    val end = carry + count
                    val complete = utf8Prefix(bytes, end)
                    if (complete > 0) yield(bytes.decodeToString(endIndex = complete))
                    carry = end - complete
                    bytes.copyInto(bytes, startIndex = complete, endIndex = end)
                }
            })
        } finally {
            gzclose(file)
        }
    }

    private fun utf8Prefix(bytes: ByteArray, end: Int): Int {
        var start = end - 1
        while (start >= 0 && end - start <= 4 && bytes[start].toInt() and 0xC0 == 0x80) start--
        if (start < 0 || end - start > 4) return end
        val lead = bytes[start].toInt() and 0xFF
        val length = when (lead) {
            in 0xC2..0xDF -> 2
            in 0xE0..0xEF -> 3
            in 0xF0..0xF4 -> 4
            else -> 1
        }
        return if (end - start < length) start else end
    }

    private fun existsAndMatches(first: String, second: String): Boolean {
        val a = gzopen(first, "rb") ?: return false
        try {
            val b = gzopen(second, "rb") ?: return false
            try {
                val left = ByteArray(chunkSize)
                val right = ByteArray(chunkSize)
                while (true) {
                    val n = left.usePinned { gzread(a, it.addressOf(0).reinterpret(), left.size.toUInt()) }
                    val m = right.usePinned { gzread(b, it.addressOf(0).reinterpret(), right.size.toUInt()) }
                    if (n < 0 || n != m || (0 until n).any { left[it] != right[it] }) return false
                    if (n == 0) return true
                }
            } finally {
                gzclose(b)
            }
        } finally {
            gzclose(a)
        }
    }

    actual fun readSmall(dir: String, name: String): String? = readChunks(dir, name) { it.joinToString("") }

    actual fun writeSmall(dir: String, name: String, text: String) {
        writeText(dir, name, text)
    }

    actual val deviceName: String get() = UIDevice.currentDevice.model
}
