package com.nuvio.app.features.livetv.data

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.livetv.model.EpgAssignment
import com.nuvio.app.features.livetv.model.EpgProgram
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types

/**
 * The guide on disk, in the same SQLite layout as the Android app's store.
 *
 * Desktop used to keep every programme in memory (about 150 MB of heap for an 11 MB XMLTV
 * file) and started empty on each launch, so the whole guide was parsed again at startup.
 * On disk the guide survives restarts (its fingerprint skips the rebuild) and only the rows a
 * screen asks for are loaded.
 */
internal actual object EpgStore {
    private const val DB_NAME = "livetv_guide.db"
    private const val DB_VERSION = 4

    /** The longest a single show is taken to run, for reading "what's on now" by start time. */
    private const val MAX_SHOW_MS = 8L * 60 * 60 * 1000

    /** Guide rows read per page when going through the whole guide. */
    private const val SCAN_PAGE = 500

    private const val PROGRAM_COLUMNS =
        "ch, start, stop, title, descr, cat, ep, icon, year, people, stalker_program, stalker_archive, stalker_source"
    private const val LIGHT_COLUMNS =
        "ch, start, stop, title, ep, icon, stalker_program, stalker_archive, stalker_source"

    private val lock = Any()
    private var connection: Connection? = null

    /** Tests point the store at a temporary folder; the app keeps it null. */
    internal var directoryForTests: java.nio.file.Path? = null
        set(value) = synchronized(lock) {
            runCatching { connection?.close() }
            connection = null
            field = value
        }

    /** One connection, used under [lock]; opened on first use. Null when the file can't be opened. */
    private fun db(): Connection? {
        connection?.let { if (!it.isClosed) return it }
        return runCatching { open() }.getOrNull()?.also { connection = it }
    }

    private fun open(): Connection {
        val dir = directoryForTests ?: DesktopStorage.rootDir.resolve("livetv")
        Files.createDirectories(dir)
        Class.forName("org.sqlite.JDBC")
        val conn = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve(DB_NAME).toAbsolutePath())
        conn.createStatement().use { st ->
            // Readers don't wait for writers: the guide stays readable while an update is stored.
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA synchronous=NORMAL")
            st.execute("PRAGMA busy_timeout=5000")
        }
        migrate(conn)
        // A log left by an earlier run (or a crash) is folded in once at open.
        conn.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") }
        return conn
    }

    private fun migrate(conn: Connection) {
        val version = conn.createStatement().use { st -> st.executeQuery("PRAGMA user_version").use { if (it.next()) it.getInt(1) else 0 } }
        if (version == DB_VERSION) return
        conn.createStatement().use { st ->
            st.execute("DROP TABLE IF EXISTS programs")
            st.execute("DROP TABLE IF EXISTS auto")
            st.execute("DROP TABLE IF EXISTS meta")
            st.execute(
                """CREATE TABLE programs (
                    ch TEXT NOT NULL, start INTEGER NOT NULL, stop INTEGER NOT NULL, title TEXT NOT NULL,
                    descr TEXT, cat TEXT, ep TEXT, icon TEXT, year INTEGER, people TEXT, stalker_program TEXT,
                    stalker_archive INTEGER NOT NULL DEFAULT 0, stalker_source TEXT)"""
            )
            createIndices(conn)
            st.execute("CREATE TABLE auto (ch TEXT PRIMARY KEY, source TEXT NOT NULL, xmltv TEXT NOT NULL)")
            st.execute("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT)")
            st.execute("PRAGMA user_version = $DB_VERSION")
        }
    }

    private fun createIndices(conn: Connection) {
        conn.createStatement().use { st ->
            st.execute("CREATE INDEX IF NOT EXISTS programs_ch_start ON programs(ch, start)")
            st.execute("CREATE INDEX IF NOT EXISTS programs_stop ON programs(stop)")
            st.execute("CREATE INDEX IF NOT EXISTS programs_start ON programs(start)")
        }
    }

    private fun dropIndices(conn: Connection) {
        conn.createStatement().use { st ->
            st.execute("DROP INDEX IF EXISTS programs_ch_start")
            st.execute("DROP INDEX IF EXISTS programs_stop")
            st.execute("DROP INDEX IF EXISTS programs_start")
        }
    }

    private inline fun <T> read(empty: T, block: (Connection) -> T): T = synchronized(lock) {
        val conn = db() ?: return empty
        try {
            block(conn)
        } catch (e: SQLException) {
            empty
        }
    }

    private inline fun write(block: (Connection) -> Unit): Unit = synchronized(lock) {
        val conn = db() ?: return@synchronized
        conn.autoCommit = false
        try {
            block(conn)
            conn.commit()
        } catch (e: SQLException) {
            runCatching { conn.rollback() }
        } finally {
            runCatching { conn.autoCommit = true }
        }
    }

    private fun exec(conn: Connection, sql: String, vararg args: Any?): Int =
        conn.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.executeUpdate()
        }

    private fun PreparedStatement.setText(index: Int, value: String?) {
        if (value == null) setNull(index, Types.VARCHAR) else setString(index, value)
    }

    private fun insertAll(conn: Connection, programs: Map<String, List<EpgProgram>>) {
        conn.prepareStatement("INSERT INTO programs ($PROGRAM_COLUMNS) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)").use { st ->
            var pending = 0
            programs.forEach { (ch, rows) ->
                rows.forEach { p ->
                    st.setString(1, ch)
                    st.setLong(2, p.startMs)
                    st.setLong(3, p.stopMs)
                    st.setString(4, p.title)
                    st.setText(5, p.description)
                    st.setText(6, p.category)
                    st.setText(7, p.episode)
                    st.setText(8, p.icon)
                    if (p.year == null) st.setNull(9, Types.INTEGER) else st.setInt(9, p.year)
                    st.setText(10, if (p.people.isEmpty()) null else p.people.joinToString("\u001F"))
                    st.setText(11, p.stalkerProgramId)
                    st.setInt(12, if (p.stalkerArchiveAvailable) 1 else 0)
                    st.setText(13, p.stalkerSourceIdentity)
                    st.addBatch()
                    if (++pending == 2_000) { st.executeBatch(); pending = 0 }
                }
            }
            if (pending > 0) st.executeBatch()
        }
    }

    private fun putAuto(conn: Connection, auto: Map<String, EpgAssignment>) {
        if (auto.isEmpty()) return
        conn.prepareStatement("INSERT OR REPLACE INTO auto (ch, source, xmltv) VALUES (?,?,?)").use { st ->
            auto.forEach { (ch, a) ->
                st.setString(1, ch); st.setString(2, a.sourceId); st.setString(3, a.xmltvId)
                st.addBatch()
            }
            st.executeBatch()
        }
    }

    private fun putFingerprint(conn: Connection, fingerprint: String) {
        exec(conn, "INSERT OR REPLACE INTO meta (k, v) VALUES ('fingerprint', ?)", fingerprint)
    }

    actual fun replaceAll(fingerprint: String, programs: Map<String, List<EpgProgram>>, auto: Map<String, EpgAssignment>) {
        write { conn ->
            dropIndices(conn)
            exec(conn, "DELETE FROM programs")
            exec(conn, "DELETE FROM auto")
            insertAll(conn, programs)
            putAuto(conn, auto)
            createIndices(conn)
            putFingerprint(conn, fingerprint)
        }
        checkpoint()
    }

    /** Folds the write-ahead log back into the database so a full rewrite doesn't leave a guide-sized log file. */
    private fun checkpoint() = synchronized(lock) {
        runCatching { db()?.createStatement()?.use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") } }
    }

    actual fun replaceChannels(
        fingerprint: String, channels: Set<String>,
        programs: Map<String, List<EpgProgram>>, auto: Map<String, EpgAssignment>,
    ) {
        write { conn ->
            deleteChannelRows(conn, channels)
            insertAll(conn, programs.filterKeys { it in channels })
            putAuto(conn, auto.filterKeys { it in channels })
            putFingerprint(conn, fingerprint)
        }
    }

    private fun deleteChannelRows(conn: Connection, channels: Collection<String>) {
        conn.prepareStatement("DELETE FROM programs WHERE ch = ?").use { programs ->
            conn.prepareStatement("DELETE FROM auto WHERE ch = ?").use { auto ->
                channels.forEach { ch ->
                    programs.setString(1, ch); programs.addBatch()
                    auto.setString(1, ch); auto.addBatch()
                }
                programs.executeBatch()
                auto.executeBatch()
            }
        }
    }

    actual fun fingerprint(): String? = read(null) { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT v FROM meta WHERE k = 'fingerprint'").use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    actual fun range(fromMs: Long, toMs: Long, light: Boolean, channels: Collection<String>?): Map<String, List<EpgProgram>> =
        read(emptyMap()) { conn -> rangeOnce(conn, fromMs, toMs, light, channels) }

    private fun rangeOnce(
        conn: Connection, fromMs: Long, toMs: Long, light: Boolean, channels: Collection<String>?,
    ): Map<String, List<EpgProgram>> {
        val out = HashMap<String, ArrayList<EpgProgram>>()
        // A guide import normally has just one identity per provider, shared by all its rows.
        val identities = HashMap<String, String>()
        val columns = if (light) LIGHT_COLUMNS else PROGRAM_COLUMNS
        fun query(chFilter: String, chArgs: List<String>) {
            val sql = "SELECT $columns FROM programs WHERE $chFilter start >= ? AND start < ? AND stop > ?"
            conn.prepareStatement(sql).use { st ->
                var i = 1
                chArgs.forEach { st.setString(i++, it) }
                st.setLong(i++, fromMs - MAX_SHOW_MS)
                st.setLong(i++, toMs)
                st.setLong(i, fromMs)
                st.executeQuery().use { rs ->
                    while (rs.next()) {
                        val p = if (light) lightProgram(rs, identities) else fullProgram(rs, identities)
                        out.getOrPut(rs.getString(1)) { ArrayList() } += p
                    }
                }
            }
        }
        if (channels != null) {
            channels.distinct().chunked(400).forEach { part ->
                query("ch IN (" + part.joinToString(",") { "?" } + ") AND", part)
            }
        } else {
            query("", emptyList())
        }
        out.values.forEach { rows -> rows.sortBy { it.startMs } }
        return out
    }

    private fun ResultSet.text(index: Int): String? = getString(index)

    private fun ResultSet.identity(index: Int, identities: HashMap<String, String>): String? =
        getString(index)?.let { identities.getOrPut(it) { it } }

    private fun lightProgram(rs: ResultSet, identities: HashMap<String, String>) = EpgProgram(
        startMs = rs.getLong(2),
        stopMs = rs.getLong(3),
        title = rs.getString(4),
        episode = rs.text(5),
        icon = rs.text(6),
        stalkerProgramId = rs.text(7),
        stalkerArchiveAvailable = rs.getInt(8) != 0,
        stalkerSourceIdentity = rs.identity(9, identities),
    )

    private fun fullProgram(rs: ResultSet, identities: HashMap<String, String>) = EpgProgram(
        startMs = rs.getLong(2),
        stopMs = rs.getLong(3),
        title = rs.getString(4),
        description = rs.text(5),
        category = rs.text(6),
        episode = rs.text(7),
        icon = rs.text(8),
        year = rs.getInt(9).takeUnless { rs.wasNull() },
        people = rs.text(10)?.split('\u001F').orEmpty(),
        stalkerProgramId = rs.text(11),
        stalkerArchiveAvailable = rs.getInt(12) != 0,
        stalkerSourceIdentity = rs.identity(13, identities),
    )

    actual fun channelRange(ch: String, fromMs: Long, toMs: Long): List<EpgProgram> =
        range(fromMs, toMs, light = false, channels = listOf(ch))[ch].orEmpty()

    actual fun program(ch: String, startMs: Long): EpgProgram? =
        range(startMs, startMs + 1, light = false, channels = listOf(ch))[ch]?.firstOrNull { it.startMs == startMs }

    actual fun findByWords(
        fromMs: Long, toMs: Long, minStopMs: Long, words: Collection<String>,
        inDescription: Boolean, limit: Int,
    ): List<Pair<String, EpgProgram>> {
        val clean = words.map { it.trim() }.filter { it.length >= 3 }.distinct()
        if (clean.isEmpty() || limit <= 0) return emptyList()
        return read(emptyList()) { conn ->
            val out = ArrayList<Pair<String, EpgProgram>>()
            val identities = HashMap<String, String>()
            val seen = HashSet<String>()
            fun like(w: String) = "%" + w.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            for (part in clean.chunked(if (inDescription) 180 else 360)) {
                if (out.size >= limit) break
                val conds = part.joinToString(" OR ") {
                    if (inDescription) "title LIKE ? ESCAPE '\\' OR descr LIKE ? ESCAPE '\\'" else "title LIKE ? ESCAPE '\\'"
                }
                val sql = "SELECT ch, start, stop, title, descr, cat, stalker_program, stalker_archive, stalker_source FROM programs " +
                    "WHERE start >= ? AND start < ? AND stop > ? AND ($conds) LIMIT ${limit - out.size}"
                conn.prepareStatement(sql).use { st ->
                    var i = 1
                    st.setLong(i++, fromMs); st.setLong(i++, toMs); st.setLong(i++, minStopMs)
                    part.forEach { w ->
                        st.setString(i++, like(w))
                        if (inDescription) st.setString(i++, like(w))
                    }
                    st.executeQuery().use { rs ->
                        while (rs.next()) {
                            val ch = rs.getString(1)
                            val start = rs.getLong(2)
                            if (!seen.add("$ch|$start")) continue
                            out += ch to EpgProgram(
                                startMs = start,
                                stopMs = rs.getLong(3),
                                title = rs.getString(4),
                                description = rs.text(5),
                                category = rs.text(6),
                                stalkerProgramId = rs.text(7),
                                stalkerArchiveAvailable = rs.getInt(8) != 0,
                                stalkerSourceIdentity = rs.identity(9, identities),
                            )
                        }
                    }
                }
            }
            out
        }
    }

    private class ScanRow(val rowId: Long, val ch: String, val start: Long, val stop: Long, val title: String, val descr: String?, val cat: String?)

    actual fun scanListings(
        fromMs: Long, toMs: Long, minStopMs: Long,
        onRow: (ch: String, start: Long, stop: Long, title: String, descr: String?, cat: String?) -> Unit,
    ) {
        // A page at a time following the start-time index; the lock is released before the
        // callbacks run, so a guide update can land between pages and the caller may read the store.
        var lastStart = fromMs - 1
        var lastRow = 0L
        while (true) {
            val page = read(emptyList<ScanRow>()) { conn ->
                conn.prepareStatement(
                    "SELECT rowid, ch, start, stop, title, descr, cat FROM programs WHERE start >= ? AND start < ? AND stop > ?" +
                        " AND (start > ? OR (start = ? AND rowid > ?)) ORDER BY start, rowid LIMIT $SCAN_PAGE"
                ).use { st ->
                    // The lower bound moves with the page, so each page seeks the start index
                    // instead of re-reading every row from fromMs (quadratic on a full guide).
                    st.setLong(1, maxOf(fromMs, lastStart)); st.setLong(2, toMs); st.setLong(3, minStopMs)
                    st.setLong(4, lastStart); st.setLong(5, lastStart); st.setLong(6, lastRow)
                    st.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(ScanRow(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getString(5), rs.text(6), rs.text(7)))
                            }
                        }
                    }
                }
            }
            page.forEach { onRow(it.ch, it.start, it.stop, it.title, it.descr, it.cat) }
            if (page.size < SCAN_PAGE) return
            lastStart = page.last().start
            lastRow = page.last().rowId
        }
    }

    actual fun nowNext(channels: Collection<String>, nowMs: Long): Map<String, NowNext> {
        if (channels.isEmpty()) return emptyMap()
        // Shows on now, plus the next few hours, read light.
        val window = range(nowMs, nowMs + 6 * 60 * 60 * 1000L, light = true, channels = channels)
        val out = HashMap<String, NowNext>(window.size)
        window.forEach { (ch, rows) ->
            val now = rows.firstOrNull { it.isLive(nowMs) }
            val next = rows.firstOrNull { it.startMs >= (now?.stopMs ?: nowMs) }
            out[ch] = NowNext(now, next)
        }
        return out
    }

    actual fun autoMatches(): Map<String, EpgAssignment> = read(emptyMap()) { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT ch, source, xmltv FROM auto").use { rs ->
                buildMap { while (rs.next()) put(rs.getString(1), EpgAssignment(rs.getString(2), rs.getString(3))) }
            }
        }
    }

    actual fun deleteChannels(channels: Collection<String>) {
        if (channels.isEmpty()) return
        write { conn -> deleteChannelRows(conn, channels.toSet()) }
    }

    actual fun purgeOlderThan(beforeMs: Long): Int {
        var removed = 0
        write { conn -> removed = exec(conn, "DELETE FROM programs WHERE stop < ?", beforeMs) }
        return removed
    }

    actual fun compact() {
        synchronized(lock) {
            val conn = db() ?: return
            runCatching {
                fun pragma(name: String) = conn.createStatement().use { st ->
                    st.executeQuery("PRAGMA $name").use { if (it.next()) it.getLong(1) else 0L }
                }
                val pageSize = pragma("page_size").takeIf { it > 0 } ?: 4096L
                val freePages = pragma("freelist_count")
                val pageCount = pragma("page_count")
                // Worth it once a quarter of the file (and at least 8 MB) is empty space.
                if (freePages * pageSize >= 8L * 1024 * 1024 && pageCount > 0 && freePages * 4 >= pageCount) {
                    conn.createStatement().use { it.execute("VACUUM") }
                }
            }
        }
    }

    actual fun clear() {
        write { conn ->
            exec(conn, "DELETE FROM programs")
            exec(conn, "DELETE FROM auto")
            exec(conn, "DELETE FROM meta")
        }
    }

    actual fun count(): Long = read(0L) { conn ->
        conn.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM programs").use { if (it.next()) it.getLong(1) else 0L } }
    }
}
