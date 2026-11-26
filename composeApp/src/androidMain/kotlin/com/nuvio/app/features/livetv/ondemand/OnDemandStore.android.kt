package com.nuvio.app.features.livetv.ondemand

import com.nuvio.stalker.StalkerStreamRef
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal actual object OnDemandStore {
    private const val DB_NAME = "livetv_on_demand.db"
    private const val DB_VERSION = 3

    private var helper: Helper? = null

    fun initialize(context: Context) {
        if (helper == null) helper = Helper(context.applicationContext)
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        init {
            // Browsing On Demand doesn't wait while an import is writing.
            setWriteAheadLoggingEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE items (
                    pl TEXT NOT NULL, kind TEXT NOT NULL, id TEXT NOT NULL,
                    name TEXT NOT NULL, norm TEXT NOT NULL, icon TEXT, cat TEXT NOT NULL,
                    ext TEXT, tmdb TEXT, year INTEGER, rating TEXT, added INTEGER NOT NULL DEFAULT 0,
                    genre TEXT, h INTEGER, url TEXT, stalker TEXT, stalker_variant TEXT, plot TEXT, duration INTEGER,
                    fl INTEGER,
                    PRIMARY KEY (pl, kind, id))"""
            )
            createIndices(db)
            db.execSQL(
                """CREATE TABLE categories (
                    pl TEXT NOT NULL, kind TEXT NOT NULL, id TEXT NOT NULL, name TEXT NOT NULL,
                    position INTEGER NOT NULL, PRIMARY KEY (pl, kind, id))"""
            )
            db.execSQL("CREATE TABLE posters (uid TEXT PRIMARY KEY, poster TEXT, checked INTEGER NOT NULL, title TEXT, tmdb TEXT)")
            db.execSQL("CREATE TABLE prefixes (pl TEXT NOT NULL, prefix TEXT NOT NULL, PRIMARY KEY (pl, prefix))")
            createLanguageTable(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE items ADD COLUMN stalker TEXT")
                db.execSQL("ALTER TABLE items ADD COLUMN stalker_variant TEXT")
                db.execSQL("ALTER TABLE items ADD COLUMN plot TEXT")
                db.execSQL("ALTER TABLE items ADD COLUMN duration INTEGER")
            }
            // Version 3: each title's original language and a mark on titles in another language
            // ("Hide Non-English Titles"). One new column and table: everything imported is kept.
            if (oldVersion < 3) {
                runCatching { db.execSQL("ALTER TABLE items ADD COLUMN fl INTEGER") }
                // Titles are cleaned better now ("Elf 4K [4K]", "Barbie (Kor Sub)"): forget the
                // old "no poster found" answers so they're looked up again.
                runCatching { db.execSQL("DELETE FROM posters WHERE poster = '' AND (tmdb IS NULL OR tmdb = '')") }
                createLanguageTable(db)
            }
        }
    }

    private fun createLanguageTable(db: SQLiteDatabase) {
        // k: the title's key (VodTitles.languageKey); lang: "en", "fr"…, or "" when TMDB didn't know it.
        db.execSQL("CREATE TABLE IF NOT EXISTS langs (k TEXT PRIMARY KEY, lang TEXT NOT NULL, checked INTEGER NOT NULL)")
    }

    private const val FL_SURE = 1
    private const val FL_GUESS = 2

    /** "Hide Non-English Titles" is on: lists leave marked titles out. */
    @Volatile private var hideForeign = false

    private fun createIndices(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS items_cat ON items(kind, pl, cat)")
        db.execSQL("CREATE INDEX IF NOT EXISTS items_norm ON items(kind, norm)")
        db.execSQL("CREATE INDEX IF NOT EXISTS items_tmdb ON items(kind, tmdb)")
        db.execSQL("CREATE INDEX IF NOT EXISTS items_added ON items(kind, added)")
    }

    private val writable: SQLiteDatabase get() = (helper ?: error("OnDemandStore not initialized")).writableDatabase
    private val readable: SQLiteDatabase? get() = helper?.readableDatabase

    private fun <T> tx(db: SQLiteDatabase, publication: ((() -> Unit) -> Unit)? = null, block: () -> T): T {
        db.beginTransaction()
        var ended = false
        try {
            val result = block()
            val commit = {
                db.setTransactionSuccessful()
                ended = true
                db.endTransaction()
            }
            if (publication != null) publication(commit) else commit()
            return result
        } finally {
            if (!ended) db.endTransaction()
        }
    }

    /** A title's details as one number: the same number means nothing to write. */
    private fun fingerprint(it: VodItem): Long {
        var h = 1125899906842597L
        for (s in arrayOf(it.name, it.icon, it.categoryId, it.ext, it.tmdbId, it.year?.toString(), it.rating, it.addedSec.toString(), it.genre, it.url, it.stalker?.encode(), it.stalkerSeriesVariant, it.plot, it.durationSecs?.toString())) {
            h = 31 * h + (s?.hashCode() ?: 7)
        }
        return if (h == 0L) 1L else h
    }

    /** A title already saved: its fingerprint, and whether the provider still lists it. */
    private class ImportedItem(var fingerprint: Long, var seen: Boolean = false)

    actual fun replace(playlistId: String, kind: VodKind, categories: List<Pair<String, String>>, items: Sequence<VodItem>, publication: ((() -> Unit) -> Unit)?): Int {
        val db = writable
        // One entry per title (no second set of every id alongside it).
        val existing = HashMap<String, ImportedItem>()
        db.rawQuery("SELECT id, h FROM items WHERE pl = ? AND kind = ?", arrayOf(playlistId, kind.key)).use { c ->
            while (c.moveToNext()) existing[c.getString(0)] = ImportedItem(if (c.isNull(1)) 0L else c.getLong(1))
        }
        // The first import of a catalog writes with the search indexes off and builds them once
        // at the end, much quicker than keeping them up to date title by title.
        val bulk = existing.isEmpty()
        var unseen = existing.size
        var count = 0
        tx(db, publication) {
            if (bulk) {
                db.execSQL("DROP INDEX IF EXISTS items_cat")
                db.execSQL("DROP INDEX IF EXISTS items_norm")
                db.execSQL("DROP INDEX IF EXISTS items_tmdb")
                db.execSQL("DROP INDEX IF EXISTS items_added")
            }
            db.delete("categories", "pl = ? AND kind = ?", arrayOf(playlistId, kind.key))
            categories.forEachIndexed { i, (id, name) ->
                db.insertWithOnConflict("categories", null, ContentValues().apply {
                    put("pl", playlistId); put("kind", kind.key); put("id", id); put("name", name); put("position", i)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            val stmt = db.compileStatement(
                "INSERT OR REPLACE INTO items (pl, kind, id, name, norm, icon, cat, ext, tmdb, year, rating, added, genre, h, url, stalker, stalker_variant, plot, duration) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
            )
            items.forEach { it ->
                count++
                val previous = existing[it.id]
                if (previous != null && !previous.seen) {
                    previous.seen = true
                    unseen--
                }
                val h = fingerprint(it)
                if (previous?.fingerprint == h) return@forEach
                stmt.clearBindings()
                stmt.bindString(1, playlistId)
                stmt.bindString(2, kind.key)
                stmt.bindString(3, it.id)
                stmt.bindString(4, it.name)
                stmt.bindString(5, VodTitles.normalize(it.name))
                it.icon?.let { v -> stmt.bindString(6, v) } ?: stmt.bindNull(6)
                stmt.bindString(7, it.categoryId)
                it.ext?.let { v -> stmt.bindString(8, v) } ?: stmt.bindNull(8)
                it.tmdbId?.let { v -> stmt.bindString(9, v) } ?: stmt.bindNull(9)
                it.year?.let { v -> stmt.bindLong(10, v.toLong()) } ?: stmt.bindNull(10)
                it.rating?.let { v -> stmt.bindString(11, v) } ?: stmt.bindNull(11)
                stmt.bindLong(12, it.addedSec)
                it.genre?.let { v -> stmt.bindString(13, v) } ?: stmt.bindNull(13)
                stmt.bindLong(14, h)
                it.url?.let { v -> stmt.bindString(15, v) } ?: stmt.bindNull(15)
                it.stalker?.let { v -> stmt.bindString(16, v.encode()) } ?: stmt.bindNull(16)
                it.stalkerSeriesVariant?.let { v -> stmt.bindString(17, v) } ?: stmt.bindNull(17)
                it.plot?.let { v -> stmt.bindString(18, v) } ?: stmt.bindNull(18)
                it.durationSecs?.let { v -> stmt.bindLong(19, v) } ?: stmt.bindNull(19)
                stmt.executeInsert()
                // A title listed twice is written once; a changed copy keeps the last one.
                if (previous == null) existing[it.id] = ImportedItem(h, seen = true) else previous.fingerprint = h
            }
            // Titles the provider no longer lists.
            if (unseen > 0) {
                db.compileStatement("DELETE FROM items WHERE pl = ? AND kind = ? AND id = ?").use { del ->
                    existing.forEach { (id, state) ->
                        if (!state.seen) {
                            del.clearBindings(); del.bindString(1, playlistId); del.bindString(2, kind.key); del.bindString(3, id)
                            del.executeUpdateDelete()
                        }
                    }
                }
            }
            if (bulk) createIndices(db)
        }
        return count
    }

    actual fun removeExcept(keepPlaylistIds: Set<String>): Set<String> {
        val db = writable
        val existing = HashSet<String>()
        db.rawQuery("SELECT DISTINCT pl FROM items UNION SELECT DISTINCT pl FROM categories", null).use { c ->
            while (c.moveToNext()) existing += c.getString(0)
        }
        val dropped = existing - keepPlaylistIds
        tx(db) {
            dropped.forEach { pl ->
                db.delete("items", "pl = ?", arrayOf(pl))
                db.delete("categories", "pl = ?", arrayOf(pl))
                db.delete("prefixes", "pl = ?", arrayOf(pl))
            }
        }
        return dropped
    }

    actual fun hasAny(): Boolean {
        val db = readable ?: return false
        return db.rawQuery("SELECT 1 FROM items LIMIT 1", null).use { it.moveToFirst() }
    }

    actual fun count(kind: VodKind): Int {
        val db = readable ?: return 0
        return db.rawQuery("SELECT COUNT(*) FROM items WHERE kind = ?", arrayOf(kind.key)).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    actual fun categories(kind: VodKind): List<VodCategory> {
        val db = readable ?: return emptyList()
        val out = ArrayList<VodCategory>()
        db.rawQuery(
            """SELECT c.pl, c.id, c.name, (SELECT COUNT(*) FROM items i WHERE i.pl = c.pl AND i.kind = c.kind AND i.cat = c.id${if (hideForeign) " AND i.fl IS NULL" else ""})
               FROM categories c WHERE c.kind = ? ORDER BY c.pl, c.position""",
            arrayOf(kind.key)
        ).use { c ->
            while (c.moveToNext()) {
                val n = c.getInt(3)
                if (n > 0) out += VodCategory(c.getString(0), kind, c.getString(1), c.getString(2), n)
            }
        }
        return out
    }

    actual fun items(kind: VodKind, category: VodCategory?, excludeCategories: Set<String>, limit: Int, offset: Int, sort: VodSort, merge: Boolean): List<VodItem> {
        val where = StringBuilder("kind = ?")
        val args = arrayListOf(kind.key)
        if (category != null) {
            where.append(" AND pl = ? AND cat = ?")
            args += category.playlistId; args += category.id
        }
        excludeCategories.forEach { uid ->
            val parts = uid.split(':', limit = 3)
            if (parts.size == 3 && parts[0] == kind.key) {
                where.append(" AND NOT (pl = ? AND cat = ?)")
                args += parts[1]; args += parts[2]
            }
        }
        if (hideForeign) where.append(" AND fl IS NULL")
        val order = sort.orderBy("rowid")
        val group = if (merge) " GROUP BY $VOD_DUP_KEY" else ""
        val copies = if (merge) ", COUNT(*) AS copies" else ""
        // A fixed last tie-break: titles that sort the same must come back in the same order on
        // every page, or a title shows on two pages and another on none.
        val tieBreak = if (merge) "MIN(rowid)" else "rowid"
        return query("SELECT *$copies FROM items WHERE $where$group ORDER BY $order, $tieBreak LIMIT $limit OFFSET $offset", args)
    }

    actual fun search(kind: VodKind, text: String, excludeCategories: Set<String>, limit: Int, sort: VodSort, merge: Boolean): List<VodItem> {
        val words = VodTitles.normalizeWords(text)
        if (words.isEmpty()) return emptyList()
        val result = runCatching {
            val joined = words.joinToString("")
            val starts = rowids(
                "SELECT rowid FROM items WHERE kind = ? AND norm >= ? AND norm < ? LIMIT 1500",
                listOf(kind.key, joined, joined + "￿")
            )
            val anywhere = rowids(
                "SELECT rowid FROM items WHERE kind = ? AND " + words.joinToString(" AND ") { "instr(norm, ?) > 0" } + " LIMIT 4000",
                listOf(kind.key) + words
            )
            val ids = LinkedHashSet<Long>(starts).apply { addAll(anywhere) }
            if (ids.isEmpty()) return@runCatching emptyList()
            val group = if (merge) " GROUP BY $VOD_DUP_KEY" else ""
            val copies = if (merge) ", COUNT(*) AS copies" else ""
            // Best first: what you typed at the start of the name, then the shortest names.
            val first = if (sort == VodSort.DEFAULT && starts.isNotEmpty()) "(rowid IN (${starts.joinToString(",")})) DESC, " else ""
            val foreign = if (hideForeign) " AND fl IS NULL" else ""
            query(
                "SELECT *$copies FROM items WHERE rowid IN (${ids.joinToString(",")})$foreign$group ORDER BY $first${sort.orderBy("length(name)")} LIMIT $limit",
                emptyList()
            )
        }.getOrElse {
            // Anything unexpected: the plain search.
            val where = StringBuilder("kind = ?")
            val args = arrayListOf(kind.key)
            words.forEach { w -> where.append(" AND norm LIKE ?"); args += "%$w%" }
            if (hideForeign) where.append(" AND fl IS NULL")
            val group = if (merge) " GROUP BY $VOD_DUP_KEY" else ""
            val copies = if (merge) ", COUNT(*) AS copies" else ""
            query("SELECT *$copies FROM items WHERE $where$group ORDER BY ${sort.orderBy("length(name)")} LIMIT $limit", args)
        }
        return result.filter { "${it.kind.key}:${it.playlistId}:${it.categoryId}" !in excludeCategories }
    }

    actual fun byTmdb(kind: VodKind, tmdbId: String): List<VodItem> =
        query("SELECT * FROM items WHERE kind = ? AND tmdb = ?", arrayListOf(kind.key, tmdbId))

    actual fun byTitle(kind: VodKind, title: String): List<VodItem> {
        val norm = VodTitles.normalize(title)
        if (norm.isBlank()) return emptyList()
        return query("SELECT * FROM items WHERE kind = ? AND norm = ?", arrayListOf(kind.key, norm))
    }

    actual fun versions(item: VodItem): List<VodItem> =
        if (!item.tmdbId.isNullOrBlank()) query("SELECT * FROM items WHERE kind = ? AND tmdb = ?", arrayListOf(item.kind.key, item.tmdbId))
        else query(
            "SELECT * FROM items WHERE kind = ? AND (tmdb IS NULL OR tmdb = '') AND norm = ? AND IFNULL(year, 0) = ?",
            arrayListOf(item.kind.key, VodTitles.normalize(item.name), (item.year ?: 0).toString())
        )

    actual fun categoryNames(playlistId: String, kind: VodKind): Map<String, String> {
        val db = readable ?: return emptyMap()
        val out = HashMap<String, String>()
        db.rawQuery("SELECT id, name FROM categories WHERE pl = ? AND kind = ?", arrayOf(playlistId, kind.key)).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = c.getString(1)
        }
        return out
    }

    actual fun item(playlistId: String, kind: VodKind, id: String): VodItem? =
        query("SELECT * FROM items WHERE pl = ? AND kind = ? AND id = ?", arrayListOf(playlistId, kind.key, id)).firstOrNull()

    actual fun learnPrefixes(playlistId: String): Set<String> {
        val db = writable
        val names = ArrayList<String>()
        db.rawQuery("SELECT name FROM items WHERE pl = ?", arrayOf(playlistId)).use { c -> while (c.moveToNext()) names += c.getString(0) }
        val learned = VodTitles.learnPrefixes(names.asSequence())
        tx(db) {
            db.delete("prefixes", "pl = ?", arrayOf(playlistId))
            learned.forEach { p ->
                db.insertWithOnConflict("prefixes", null, ContentValues().apply { put("pl", playlistId); put("prefix", p) }, SQLiteDatabase.CONFLICT_IGNORE)
            }
        }
        return loadPrefixes()
    }

    actual fun hasLearnedPrefixes(playlistId: String): Boolean {
        val db = readable ?: return false
        return db.rawQuery("SELECT 1 FROM prefixes WHERE pl = ? LIMIT 1", arrayOf(playlistId)).use { it.moveToFirst() }
    }

    actual fun loadPrefixes(): Set<String> {
        val db = readable ?: return emptySet()
        val set = HashSet<String>()
        runCatching { db.rawQuery("SELECT prefix FROM prefixes", null).use { c -> while (c.moveToNext()) set += c.getString(0) } }
        return set
    }

    actual fun cachedMatch(uid: String): VodMatch? {
        val db = readable ?: return null
        return db.rawQuery("SELECT poster, checked, title, tmdb FROM posters WHERE uid = ?", arrayOf(uid)).use { c ->
            if (c.moveToFirst()) VodMatch(
                poster = c.getString(0) ?: "",
                checkedMs = c.getLong(1),
                title = if (c.isNull(2)) null else c.getString(2),
                tmdbId = if (c.isNull(3)) null else c.getString(3),
            ) else null
        }
    }

    actual fun cacheMatch(uid: String, match: VodMatch) {
        writable.insertWithOnConflict("posters", null, ContentValues().apply {
            put("uid", uid); put("poster", match.poster); put("checked", match.checkedMs)
            if (match.title != null) put("title", match.title) else putNull("title")
            if (match.tmdbId != null) put("tmdb", match.tmdbId) else putNull("tmdb")
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    actual fun setHideForeign(on: Boolean) { hideForeign = on }

    actual fun needingLanguage(limit: Int, retryBefore: Long): List<VodItem> =
        query(
            "SELECT * FROM items WHERE (fl IS NULL OR fl = $FL_GUESS) AND NOT EXISTS (SELECT 1 FROM langs l WHERE l.k = items.kind || $VOD_DUP_KEY AND (l.lang != '' OR l.checked > ?)) " +
                "GROUP BY items.kind || $VOD_DUP_KEY ORDER BY MAX(IFNULL(fl, 0)) DESC, MAX(added) DESC LIMIT $limit",
            arrayListOf(retryBefore.toString()),
        )

    actual fun knownLanguages(keys: Collection<String>, retryBefore: Long): Set<String> {
        val db = readable ?: return keys.toSet()
        if (keys.isEmpty()) return emptySet()
        val out = HashSet<String>()
        keys.distinct().chunked(400).forEach { part ->
            db.rawQuery(
                "SELECT k FROM langs WHERE (lang != '' OR checked > ?) AND k IN (${part.joinToString(",") { "?" }})",
                (listOf(retryBefore.toString()) + part).toTypedArray(),
            ).use { c -> while (c.moveToNext()) out += c.getString(0) }
        }
        return out
    }

    actual fun saveLanguages(found: Map<String, String>): Int {
        if (found.isEmpty()) return 0
        val db = writable
        val now = System.currentTimeMillis()
        var changed = 0
        tx(db) {
            val put = db.compileStatement("INSERT OR REPLACE INTO langs (k, lang, checked) VALUES (?,?,?)")
            // Another language: marked for sure (a guess becomes certain). English: a guess is undone.
            val markTmdb = db.compileStatement("UPDATE items SET fl = $FL_SURE WHERE (fl IS NULL OR fl = $FL_GUESS) AND kind = ? AND tmdb = ?")
            val markName = db.compileStatement("UPDATE items SET fl = $FL_SURE WHERE (fl IS NULL OR fl = $FL_GUESS) AND kind = ? AND norm = ? AND items.kind || $VOD_DUP_KEY = ?")
            val backTmdb = db.compileStatement("UPDATE items SET fl = NULL WHERE fl = $FL_GUESS AND kind = ? AND tmdb = ?")
            val backName = db.compileStatement("UPDATE items SET fl = NULL WHERE fl = $FL_GUESS AND kind = ? AND norm = ? AND items.kind || $VOD_DUP_KEY = ?")
            for ((k, lang) in found) {
                put.clearBindings(); put.bindString(1, k); put.bindString(2, lang); put.bindLong(3, now); put.executeInsert()
                val foreign = VodTitles.isForeignLanguage(lang)
                if (!foreign && !lang.equals("en", ignoreCase = true)) continue
                val kind = if (k.startsWith(VodKind.SERIES.key)) VodKind.SERIES.key else VodKind.MOVIE.key
                val rest = k.removePrefix(kind)
                if (rest.startsWith("t")) {
                    val stmt = if (foreign) markTmdb else backTmdb
                    stmt.clearBindings(); stmt.bindString(1, kind); stmt.bindString(2, rest.substring(1))
                    changed += stmt.executeUpdateDelete()
                } else if (rest.startsWith("n")) {
                    val norm = rest.substring(1).substringBeforeLast('|')
                    val stmt = if (foreign) markName else backName
                    stmt.clearBindings(); stmt.bindString(1, kind); stmt.bindString(2, norm); stmt.bindString(3, k)
                    changed += stmt.executeUpdateDelete()
                }
            }
        }
        return changed
    }

    actual fun markForeign(playlistId: String) {
        val db = writable
        tx(db) {
            db.execSQL(
                "UPDATE items SET fl = $FL_SURE WHERE pl = ? AND (fl IS NULL OR fl = $FL_GUESS) AND items.kind || $VOD_DUP_KEY IN (SELECT k FROM langs WHERE lang != '' AND lang != 'en')",
                arrayOf(playlistId),
            )
            val foreignGroups = ArrayList<Pair<String, String>>()
            db.rawQuery("SELECT kind, id, name FROM categories WHERE pl = ?", arrayOf(playlistId)).use { c ->
                while (c.moveToNext()) if (VodLanguageTags.groupIsForeign(c.getString(2))) foreignGroups += c.getString(0) to c.getString(1)
            }
            foreignGroups.forEach { (kind, cat) ->
                // Only a guess: a group can hold the odd English title (an Australian film in "Netflix Asia").
                db.execSQL("UPDATE items SET fl = $FL_GUESS WHERE pl = ? AND kind = ? AND cat = ? AND fl IS NULL", arrayOf(playlistId, kind, cat))
            }
            val rows = ArrayList<Pair<Long, Int>>()
            db.rawQuery("SELECT rowid, name, fl FROM items WHERE pl = ? AND (fl IS NULL OR fl = $FL_GUESS)", arrayOf(playlistId)).use { c ->
                while (c.moveToNext()) {
                    val signal = VodLanguageTags.titleSignal(c.getString(1))
                    val current = if (c.isNull(2)) 0 else c.getInt(2)
                    if (signal == VodLanguageTags.SURE || (signal == VodLanguageTags.GUESS && current == 0)) rows += c.getLong(0) to signal
                }
            }
            if (rows.isNotEmpty()) {
                val stmt = db.compileStatement("UPDATE items SET fl = ? WHERE rowid = ?")
                rows.forEach { (id, fl) -> stmt.clearBindings(); stmt.bindLong(1, fl.toLong()); stmt.bindLong(2, id); stmt.executeUpdateDelete() }
            }
            // Guesses TMDB has already said are English stay on the lists.
            db.execSQL(
                "UPDATE items SET fl = NULL WHERE pl = ? AND fl = $FL_GUESS AND items.kind || $VOD_DUP_KEY IN (SELECT k FROM langs WHERE lang = 'en')",
                arrayOf(playlistId),
            )
        }
    }

    actual fun markForeignAll() {
        val db = readable ?: return
        val ids = ArrayList<String>()
        db.rawQuery("SELECT DISTINCT pl FROM categories", null).use { c -> while (c.moveToNext()) ids += c.getString(0) }
        ids.forEach { runCatching { markForeign(it) } }
    }

    actual fun compact() {
        val db = writable
        runCatching {
            val pageSize = db.rawQuery("PRAGMA page_size", null).use { if (it.moveToFirst()) it.getLong(0) else 4096L }
            val freePages = db.rawQuery("PRAGMA freelist_count", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
            val pageCount = db.rawQuery("PRAGMA page_count", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
            if (freePages * pageSize >= 8L * 1024 * 1024 && pageCount > 0 && freePages * 4 >= pageCount) db.execSQL("VACUUM")
        }
    }

    private fun query(sql: String, args: List<String>): List<VodItem> {
        val db = readable ?: return emptyList()
        val out = ArrayList<VodItem>()
        db.rawQuery(sql, args.toTypedArray()).use { c -> while (c.moveToNext()) out += c.toItem() }
        return out
    }

    private fun rowids(sql: String, args: List<String>): List<Long> {
        val db = readable ?: return emptyList()
        val out = ArrayList<Long>()
        db.rawQuery(sql, args.toTypedArray()).use { c -> while (c.moveToNext()) out += c.getLong(0) }
        return out
    }

    private fun Cursor.toItem(): VodItem = VodItem(
        playlistId = getString(getColumnIndexOrThrow("pl")),
        kind = VodKind.of(getString(getColumnIndexOrThrow("kind"))),
        id = getString(getColumnIndexOrThrow("id")),
        name = getString(getColumnIndexOrThrow("name")),
        icon = getStringOrNull("icon"),
        categoryId = getString(getColumnIndexOrThrow("cat")),
        ext = getStringOrNull("ext"),
        tmdbId = getStringOrNull("tmdb"),
        year = getColumnIndexOrThrow("year").let { i -> if (isNull(i)) null else getInt(i) },
        rating = getStringOrNull("rating"),
        addedSec = getLong(getColumnIndexOrThrow("added")),
        genre = getStringOrNull("genre"),
        url = getStringOrNull("url"),
        stalker = getStringOrNull("stalker")?.let(StalkerStreamRef::decode),
        stalkerSeriesVariant = getStringOrNull("stalker_variant"),
        plot = getStringOrNull("plot"),
        durationSecs = getColumnIndexOrThrow("duration").let { i -> if (isNull(i)) null else getLong(i) },
        copies = getColumnIndex("copies").takeIf { it >= 0 }?.let { i -> getInt(i) } ?: 1,
    )

    private fun Cursor.getStringOrNull(col: String): String? =
        getColumnIndexOrThrow(col).let { i -> if (isNull(i)) null else getString(i) }
}
