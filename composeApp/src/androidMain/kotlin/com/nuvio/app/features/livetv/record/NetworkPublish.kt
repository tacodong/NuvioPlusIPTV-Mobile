package com.nuvio.app.features.livetv.record

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Copies a recording made on this phone to the network folder in the TV app's layout, so the
 * TVs (and this phone) list it and play it like one of theirs:
 *  - the video as one file per show: "<Show>/<Show> - S01E02.ts" (or the date it aired);
 *  - in ".nuvio/recordings/<id>": join.idx (where each piece of the playlist is in that file),
 *    index.m3u8 (the playlist) and info.json (its details, written last: they say the rest is complete).
 *
 * A TS recording is cut into short pieces on the way across, each starting with the stream's
 * program tables and cut where a picture starts cleanly, as the TV app's recorder does, so it can
 * be skipped through. An MP4 one (fragmented) is copied as it is and its fragments listed.
 *
 * Where it's going is kept in the recording's marker file on the phone, so a copy that broke
 * part way is written over by the next try instead of leaving a second copy.
 */
internal object NetworkPublish {
    private const val PIECE_SEC = 6.0

    fun publish(folder: SmbFolder, rec: LiveTvRecording, local: File, onProgress: (Float) -> Unit): String {
        if (!local.isFile || local.length() <= 0L) throw IOException("the recording's file is missing on this phone")
        val mp4 = local.name.endsWith(".mp4", ignoreCase = true)
        val marker = File(local.parentFile, NetworkUploads.MARKER)
        val before = readMarker(marker)

        // Where it goes: kept from an earlier try, else a name not taken yet.
        val show = Names.clean(rec.title)
        val base = Names.baseName(rec)
        val ext = if (mp4) "mp4" else "ts"
        folder.mkdirs(show)
        val file = before["file"]?.takeIf { it.isNotBlank() } ?: run {
            var name = "$show/$base.$ext"
            var n = 2
            while (folder.fileExists(name) || folder.fileExists(name + Names.IN_PROGRESS)) { name = "$show/$base ($n).$ext"; n++ }
            name
        }
        val id = before["id"]?.takeIf { it.isNotBlank() } ?: run {
            val taken = folder.listFolders(NetworkLibrary.META_DIR).toSet()
            var candidate = rec.id
            var n = 2
            while (candidate in taken) { candidate = "${rec.id}-$n"; n++ }
            candidate
        }
        writeMarker(marker, mapOf("file" to file, "id" to id))

        // The video, as ".part" until it's all there.
        val part = file + Names.IN_PROGRESS
        runCatching { folder.delete(part) }
        val writer = RemoteWriter(folder, part)
        val total = local.length()
        if (mp4) {
            val (init, pieces) = Mp4Pieces.scan(local, rec.durationSec)
            copyRaw(local, writer) { read -> onProgress((read.toDouble() / total).toFloat().coerceIn(0f, 0.98f)) }
            writer.finish()
            finishFile(folder, writer, part, file)
            // The start part is found through the join index too.
            writeDetails(folder, rec, id, base, file, listOf(init) + pieces, pieces, Mp4Pieces.INIT_NAME, writer.position)
        } else {
            val secPerByte = if (rec.durationSec > 0) rec.durationSec / total else 8.0 / 4_000_000.0
            val cutter = TsCutter(writer, PIECE_SEC, secPerByte)
            local.inputStream().buffered(1 shl 20).use { input ->
                val buf = ByteArray(1 shl 16)
                var read = 0L
                var shown = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    cutter.write(buf, 0, n)
                    read += n
                    if (read - shown > (4L shl 20)) { shown = read; onProgress((read.toDouble() / total).toFloat().coerceIn(0f, 0.98f)) }
                }
            }
            cutter.finish()
            writer.finish()
            val pieces = cutter.pieces
            if (pieces.isEmpty()) throw IOException("no video could be read from the recording")
            finishFile(folder, writer, part, file)
            writeDetails(folder, rec, id, base, file, pieces, pieces, null, writer.position)
        }
        onProgress(1f)
        return id
    }

    /** Checks the ".part" file is all there, then gives it its name. */
    private fun finishFile(folder: SmbFolder, writer: RemoteWriter, part: String, file: String) {
        folder.setLength(part, writer.position)
        val got = folder.size(part) ?: -1L
        if (got != writer.position) throw IOException("the copy on the network folder came out the wrong size ($got of ${writer.position} bytes)")
        // Ours from an earlier try that got this far.
        runCatching { if (folder.fileExists(file)) folder.delete(file) }
        folder.rename(part, file)
    }

    /** join.idx, index.m3u8, then info.json. */
    private fun writeDetails(
        folder: SmbFolder, rec: LiveTvRecording, id: String, base: String, file: String,
        indexed: List<Piece>, played: List<Piece>, initName: String?, size: Long,
    ) {
        val dir = NetworkLibrary.META_DIR + "/" + id
        folder.mkdirs(dir)
        val idx = StringBuilder("NUVIO-JOIN 1\n")
        idx.append("M\tJOIN\n").append("B\t").append(base).append('\n')
        idx.append("F\t1\tN\t1\t").append(file).append('\n')
        indexed.forEach { p -> idx.append("P\t").append(p.name).append("\t1\t").append(p.offset).append('\t').append(p.length).append('\n') }
        folder.writeBytes("$dir/join.idx", idx.toString().toByteArray(Charsets.UTF_8))

        val target = maxOf(1, kotlin.math.ceil(played.maxOfOrNull { it.durationSec } ?: PIECE_SEC).toInt())
        val m3u = StringBuilder("#EXTM3U\n")
        m3u.append("#EXT-X-VERSION:").append(if (initName != null) 7 else 3).append('\n')
        m3u.append("#EXT-X-TARGETDURATION:").append(target).append('\n')
        m3u.append("#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n")
        initName?.let { m3u.append("#EXT-X-MAP:URI=\"").append(it).append("\"\n") }
        played.forEach { p ->
            if (p.discontinuity) m3u.append("#EXT-X-DISCONTINUITY\n")
            m3u.append("#EXTINF:").append(String.format(Locale.US, "%.3f", p.durationSec)).append(",\n").append(p.name).append('\n')
        }
        m3u.append("#EXT-X-ENDLIST\n")
        folder.writeBytes("$dir/index.m3u8", m3u.toString().toByteArray(Charsets.UTF_8))

        val info = rec.copy(
            id = id,
            status = RecordingStatus.DONE,
            sizeBytes = size,
            durationSec = played.sumOf { it.durationSec }.takeIf { it > 0 } ?: rec.durationSec,
        ).toJson().toString()
        folder.writeBytes("$dir/info.json", info.toByteArray(Charsets.UTF_8))
    }

    private fun copyRaw(local: File, writer: RemoteWriter, onRead: (Long) -> Unit) {
        local.inputStream().buffered(1 shl 20).use { input ->
            val buf = ByteArray(1 shl 16)
            var read = 0L
            var shown = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                writer.write(buf, 0, n)
                read += n
                if (read - shown > (4L shl 20)) { shown = read; onRead(read) }
            }
        }
    }

    private fun readMarker(f: File): Map<String, String> = runCatching {
        f.readLines().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1) } }.toMap()
    }.getOrDefault(emptyMap())

    private fun writeMarker(f: File, values: Map<String, String>) {
        runCatching { f.writeText(values.entries.joinToString("\n") { it.key + "=" + it.value }) }
    }

    /** A piece of the playlist: [length] bytes at [offset] in the show's file. */
    data class Piece(val name: String, val offset: Long, val length: Long, val durationSec: Double, val discontinuity: Boolean = false)

    /** Writes a file on the network folder in big blocks, each tried again a few times before giving up. */
    class RemoteWriter(private val folder: SmbFolder, private val rel: String) {
        private val buf = ByteArray(CHUNK)
        private var fill = 0
        private var committed = 0L
        /** When writing started, to keep it under the copy speed limit. */
        private val startNs = System.nanoTime()

        /** Bytes written so far (the next one goes here). */
        val position: Long get() = committed + fill

        fun write(b: ByteArray, off: Int, len: Int) {
            var o = off
            var left = len
            while (left > 0) {
                val n = minOf(CHUNK - fill, left)
                System.arraycopy(b, o, buf, fill, n)
                fill += n; o += n; left -= n
                if (fill == CHUNK) flush()
            }
        }

        fun finish() = flush()

        private fun flush() {
            if (fill == 0) return
            var tries = 0
            while (true) {
                try {
                    folder.writeBytesAt(rel, committed, buf, fill)
                    break
                } catch (e: Exception) {
                    if (++tries >= 4) throw e
                    Thread.sleep(2_000L * tries)
                }
            }
            committed += fill
            fill = 0
            SmbFolder.pace(startNs, committed)
        }

        private companion object { const val CHUNK = 4 shl 20 }
    }

    /** Names that work on Windows, NAS boxes and USB drives: the same as the TV app's. */
    object Names {
        const val IN_PROGRESS = ".part"
        private val EPISODE_NUMBERS = Regex("""(?i)s\s*0*(\d{1,3})\s*[.\- ]?\s*e\s*0*(\d{1,4})""")

        fun clean(text: String): String {
            val t = text.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), " ")
                .replace(Regex("\\s+"), " ").trim().trimEnd('.', ' ')
            return t.take(100).trimEnd('.', ' ').ifBlank { "Recording" }
        }

        /** "Show - S01E02", or "Show - 2026-11-21 21.00" (without the extension). */
        fun baseName(rec: LiveTvRecording): String {
            val title = clean(rec.title)
            val ep = rec.episode?.let { EPISODE_NUMBERS.find(it) }
            if (ep != null) return String.format(Locale.US, "%s - S%02dE%02d", title, ep.groupValues[1].toInt(), ep.groupValues[2].toInt())
            val aired = rec.programStartMs.takeIf { it > 0 } ?: rec.startedAtMs
            return title + " - " + SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US).format(Date(aired))
        }
    }
}

/**
 * Cuts an MPEG-TS stream into pieces of about [targetSec] as it's copied, like the TV app's
 * TsSegmenter: each piece starts with the program tables (PAT / PMT) so it plays on its own, and
 * is cut where a picture starts cleanly when the stream marks one. Lengths come from the
 * stream's own clock (PCR), else from the size ([secPerByte]). A jump in the clock (the
 * recording reconnected) starts a new piece marked as a fresh start.
 */
internal class TsCutter(
    private val out: NetworkPublish.RemoteWriter,
    private val targetSec: Double,
    private val secPerByte: Double,
) {
    val pieces = ArrayList<NetworkPublish.Piece>()

    private val pkt = ByteArray(PACKET)
    private var pktFill = 0
    private var synced = false

    private var pat: ByteArray? = null
    private val pmtPids = HashSet<Int>()
    private val pmts = LinkedHashMap<Int, ByteArray>()
    private var pcrPid = -1

    private var inPiece = false
    private var pieceOffset = 0L
    private var pieceBytes = 0L
    private var pieceStartPcr = -1L
    private var lastPcr = -1L
    private var pieceDiscontinuity = false
    private var nextIsDiscontinuity = false

    fun write(buf: ByteArray, off: Int, len: Int) {
        var i = off
        val end = off + len
        while (i < end) {
            if (!synced) {
                if (buf[i] != SYNC) { i++; continue }
                synced = true
                pktFill = 0
            }
            val take = minOf(PACKET - pktFill, end - i)
            System.arraycopy(buf, i, pkt, pktFill, take)
            pktFill += take
            i += take
            if (pktFill == PACKET) {
                pktFill = 0
                if (pkt[0] != SYNC) { synced = false; continue }
                packet(pkt)
            }
        }
    }

    fun finish() = closePiece()

    private fun packet(p: ByteArray) {
        val pid = ((p[1].toInt() and 0x1F) shl 8) or (p[2].toInt() and 0xFF)
        val pusi = (p[1].toInt() and 0x40) != 0
        val afc = (p[3].toInt() shr 4) and 0x3
        var randomAccess = false
        var pcr = -1L
        if ((afc == 2 || afc == 3) && (p[4].toInt() and 0xFF) > 0) {
            val flags = p[5].toInt() and 0xFF
            randomAccess = (flags and 0x40) != 0
            if ((flags and 0x10) != 0 && (p[4].toInt() and 0xFF) >= 7) {
                pcr = ((p[6].toLong() and 0xFF) shl 25) or ((p[7].toLong() and 0xFF) shl 17) or
                    ((p[8].toLong() and 0xFF) shl 9) or ((p[9].toLong() and 0xFF) shl 1) or
                    ((p[10].toLong() and 0x80) shr 7)
            }
        }
        if (pid == 0 && pusi) {
            pat = p.copyOf()
            readPat(p)
        } else if (pid in pmtPids && pusi) {
            pmts[pid] = p.copyOf()
            readPmt(p)
        }
        if (pcr >= 0 && (pcrPid < 0 || pid == pcrPid)) {
            if (pcrPid < 0) pcrPid = pid
            // The clock jumped (a reconnect, or the provider restarted the stream): a fresh start.
            if (inPiece && lastPcr >= 0 && (pcr < lastPcr - 90_000L || pcr - lastPcr > 10L * 90_000L)) {
                closePiece()
                nextIsDiscontinuity = true
                lastPcr = pcr
            } else {
                lastPcr = pcr
                if (inPiece && pieceStartPcr < 0) pieceStartPcr = pcr
            }
        }
        if (!inPiece) {
            if (pat == null || pmts.isEmpty()) return
            openPiece()
        } else {
            val len = currentLengthSec()
            val target = if (pieces.isEmpty()) 2.0 else targetSec
            val cleanCut = randomAccess && len >= target
            val forcedCut = len >= target * 2 && pusi && pid == pcrPid
            if (cleanCut || forcedCut || len >= target * 3) {
                closePiece()
                openPiece()
            }
        }
        out.write(p, 0, PACKET)
        pieceBytes += PACKET
    }

    private fun currentLengthSec(): Double {
        if (pieceStartPcr >= 0 && lastPcr >= pieceStartPcr) {
            val d = (lastPcr - pieceStartPcr) / 90_000.0
            if (d < 120) return d
        }
        return pieceBytes * secPerByte
    }

    private fun openPiece() {
        inPiece = true
        pieceOffset = out.position
        pieceBytes = 0L
        pat?.let { out.write(it, 0, PACKET); pieceBytes += PACKET }
        pmts.values.forEach { out.write(it, 0, PACKET); pieceBytes += PACKET }
        pieceStartPcr = lastPcr
        pieceDiscontinuity = nextIsDiscontinuity
        nextIsDiscontinuity = false
    }

    private fun closePiece() {
        if (!inPiece) return
        inPiece = false
        if (pieceBytes <= 0L) return
        val len = currentLengthSec().coerceAtLeast(0.1)
        pieces += NetworkPublish.Piece("seg${pieces.size}.ts", pieceOffset, pieceBytes, len, pieceDiscontinuity)
        pieceStartPcr = -1L
    }

    private fun readPat(p: ByteArray) {
        val start = payloadStart(p) ?: return
        var i = start + 1 + (p[start].toInt() and 0xFF)
        if (i + 8 > PACKET) return
        val sectionLen = ((p[i + 1].toInt() and 0x0F) shl 8) or (p[i + 2].toInt() and 0xFF)
        val endOfPrograms = minOf(i + 3 + sectionLen - 4, PACKET)
        i += 8
        val found = HashSet<Int>()
        while (i + 4 <= endOfPrograms) {
            val program = ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)
            val pid = ((p[i + 2].toInt() and 0x1F) shl 8) or (p[i + 3].toInt() and 0xFF)
            if (program != 0) found += pid
            i += 4
        }
        if (found.isNotEmpty() && found != pmtPids) {
            pmtPids.clear(); pmtPids += found
            pmts.keys.retainAll(found)
        }
    }

    private fun readPmt(p: ByteArray) {
        val start = payloadStart(p) ?: return
        val i = start + 1 + (p[start].toInt() and 0xFF)
        if (i + 10 > PACKET) return
        val pid = ((p[i + 8].toInt() and 0x1F) shl 8) or (p[i + 9].toInt() and 0xFF)
        if (pid != 0x1FFF) pcrPid = pid
    }

    private fun payloadStart(p: ByteArray): Int? {
        val afc = (p[3].toInt() shr 4) and 0x3
        return when (afc) {
            1 -> 4
            3 -> (5 + (p[4].toInt() and 0xFF)).takeIf { it < PACKET }
            else -> null
        }
    }

    private companion object {
        const val PACKET = 188
        const val SYNC: Byte = 0x47
    }
}

/**
 * The fragments of a fragmented MP4 recording (an HLS channel sent in MP4 pieces): the start
 * part (ftyp + moov) and fragments (moof + mdat) grouped into pieces of about six seconds. Each
 * fragment's time comes from its tfdt and its track's time scale; if those can't be read (or
 * don't add up), lengths are shared out by size.
 */
internal object Mp4Pieces {
    const val INIT_NAME = "init.mp4"
    private const val PIECE_SEC = 6.0

    private class Box(val type: String, val offset: Long, val size: Long)
    private class Frag(val offset: Long, var length: Long, val trackId: Int?, val time: Long?)

    /** The start part, and the pieces. Throws when the file isn't a fragmented MP4. */
    fun scan(file: File, totalSec: Double): Pair<NetworkPublish.Piece, List<NetworkPublish.Piece>> = RandomAccessFile(file, "r").use { raf ->
        val length = raf.length()
        val boxes = ArrayList<Box>()
        var at = 0L
        while (at + 8 <= length) {
            raf.seek(at)
            val size32 = raf.readInt().toLong() and 0xFFFFFFFFL
            val type = ByteArray(4).also { raf.readFully(it) }.toString(Charsets.ISO_8859_1)
            val size = when (size32) {
                0L -> length - at
                1L -> raf.readLong()
                else -> size32
            }
            if (size < 8) break
            boxes += Box(type, at, minOf(size, length - at))
            at += size
        }
        val firstMoof = boxes.indexOfFirst { it.type == "moof" }
        if (firstMoof < 0) throw IOException("the recording's MP4 file can't be split into pieces")
        val initEnd = boxes[firstMoof].offset

        // Time scale per track, from moov.
        val scales = HashMap<Int, Long>()
        boxes.firstOrNull { it.type == "moov" && it.offset < initEnd }?.let { moov ->
            val bytes = read(raf, moov.offset, moov.size)
            for ((_, trakStart, trakEnd) in children(bytes, 8, bytes.size).filter { it.first == "trak" }) {
                var trackId: Int? = null
                var scale: Long? = null
                for ((t, s, e) in children(bytes, trakStart, trakEnd)) {
                    if (t == "tkhd") trackId = runCatching { val v = bytes[s].toInt(); int32(bytes, s + 4 + (if (v == 1) 16 else 8)).toInt() }.getOrNull()
                    if (t == "mdia") children(bytes, s, e).firstOrNull { it.first == "mdhd" }?.let { (_, ms, _) ->
                        scale = runCatching { val v = bytes[ms].toInt(); int32(bytes, ms + 4 + (if (v == 1) 16 else 8)) }.getOrNull()
                    }
                }
                if (trackId != null && scale != null && scale!! > 0) scales[trackId!!] = scale!!
            }
        }

        // Fragments: from one moof to the next.
        val moofs = boxes.filter { it.type == "moof" }
        val frags = moofs.mapIndexed { i, m ->
            val end = if (i + 1 < moofs.size) moofs[i + 1].offset else length
            var trackId: Int? = null
            var time: Long? = null
            runCatching {
                val bytes = read(raf, m.offset, m.size)
                children(bytes, 8, bytes.size).firstOrNull { it.first == "traf" }?.let { (_, ts, te) ->
                    for ((t, s, _) in children(bytes, ts, te)) {
                        if (t == "tfhd") trackId = int32(bytes, s + 4).toInt()
                        if (t == "tfdt") time = if (bytes[s].toInt() == 1) int64(bytes, s + 4) else int32(bytes, s + 4)
                    }
                }
            }
            Frag(m.offset, end - m.offset, trackId, time)
        }

        // Each fragment's length in seconds.
        val bytesTotal = frags.sumOf { it.length }.coerceAtLeast(1L)
        val bySize = frags.map { (if (totalSec > 0) totalSec else frags.size * 2.0) * it.length / bytesTotal }
        val byTime = frags.mapIndexed { i, f ->
            val scale = f.trackId?.let { scales[it] } ?: return@mapIndexed null
            val t = f.time ?: return@mapIndexed null
            val next = frags.getOrNull(i + 1)?.takeIf { it.trackId == f.trackId }?.time ?: return@mapIndexed null
            ((next - t).toDouble() / scale).takeIf { it > 0 && it < 60 }
        }
        val known = byTime.filterNotNull()
        val durs = if (known.size >= frags.size - 1 && known.isNotEmpty()) {
            val avg = known.average()
            byTime.map { it ?: avg }.takeIf { d -> totalSec <= 0 || d.sum() in totalSec * 0.5..totalSec * 2.0 } ?: bySize
        } else bySize

        val pieces = ArrayList<NetworkPublish.Piece>()
        var start = -1L
        var len = 0L
        var sec = 0.0
        frags.forEachIndexed { i, f ->
            if (start < 0) start = f.offset
            len += f.length
            sec += durs[i]
            if (sec >= PIECE_SEC || i == frags.lastIndex) {
                pieces += NetworkPublish.Piece("seg${pieces.size}.m4s", start, len, sec.coerceAtLeast(0.1))
                start = -1L; len = 0L; sec = 0.0
            }
        }
        NetworkPublish.Piece(INIT_NAME, 0L, initEnd, 0.0) to pieces
    }

    private fun read(raf: RandomAccessFile, offset: Long, size: Long): ByteArray {
        val b = ByteArray(size.coerceAtMost(16L shl 20).toInt())
        raf.seek(offset)
        raf.readFully(b)
        return b
    }

    /** The boxes inside [from]..[to]: (type, where its content starts, where it ends). */
    private fun children(b: ByteArray, from: Int, to: Int): List<Triple<String, Int, Int>> {
        val out = ArrayList<Triple<String, Int, Int>>()
        var at = from
        while (at + 8 <= to) {
            var size = int32(b, at)
            val type = String(b, at + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) { if (at + 16 > to) break; size = int64(b, at + 8); header = 16 }
            if (size == 0L) size = (to - at).toLong()
            if (size < header || at + size > to) break
            out += Triple(type, at + header, (at + size).toInt())
            at += size.toInt()
        }
        return out
    }

    private fun int32(b: ByteArray, i: Int): Long =
        ((b[i].toLong() and 0xFF) shl 24) or ((b[i + 1].toLong() and 0xFF) shl 16) or ((b[i + 2].toLong() and 0xFF) shl 8) or (b[i + 3].toLong() and 0xFF)

    private fun int64(b: ByteArray, i: Int): Long = (int32(b, i) shl 32) or int32(b, i + 4)
}
