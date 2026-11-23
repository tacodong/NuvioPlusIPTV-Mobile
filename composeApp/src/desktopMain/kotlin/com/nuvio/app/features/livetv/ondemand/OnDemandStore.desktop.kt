package com.nuvio.app.features.livetv.ondemand

/** In-memory stand-in until Live TV is built for iOS. */
// TODO(desktop): Replace this per-session VOD index with persistent imports and language indexing.
internal actual object OnDemandStore {
    private val items = HashMap<String, VodItem>()
    private val categories = HashMap<String, List<VodCategory>>()
    private val matches = HashMap<String, VodMatch>()
    private val prefixes = HashMap<String, Set<String>>()

    private fun key(pl: String, kind: VodKind) = "$pl|${kind.key}"

    @Synchronized
    actual fun replace(playlistId: String, kind: VodKind, categories: List<Pair<String, String>>, items: Sequence<VodItem>, publication: ((() -> Unit) -> Unit)?): Int {
        val incoming = items.toList()
        val publish = {
            this.items.keys.removeAll { k -> k.startsWith(key(playlistId, kind) + "|") }
            incoming.forEach { this.items[key(playlistId, kind) + "|" + it.id] = it }
            this.categories[key(playlistId, kind)] = categories.map { (id, name) ->
                VodCategory(playlistId, kind, id, name, incoming.count { it.categoryId == id })
            }
        }
        if (publication != null) publication(publish) else publish()
        val n = incoming.size
        return n
    }

    @Synchronized
    actual fun removeExcept(keepPlaylistIds: Set<String>): Set<String> {
        val dropped = items.values.map { it.playlistId }.toSet() - keepPlaylistIds
        items.values.removeAll { it.playlistId in dropped }
        categories.keys.removeAll { k -> k.substringBefore('|') in dropped }
        return dropped
    }

    @Synchronized
    actual fun hasAny(): Boolean = items.isNotEmpty()
    @Synchronized
    actual fun count(kind: VodKind): Int = items.values.count { it.kind == kind }

    @Synchronized
    actual fun categories(kind: VodKind): List<VodCategory> =
        categories.values.flatten().filter { it.kind == kind && it.count > 0 }

    @Synchronized
    actual fun items(kind: VodKind, category: VodCategory?, excludeCategories: Set<String>, limit: Int, offset: Int, sort: VodSort, merge: Boolean): List<VodItem> =
        items.values.filter { it.kind == kind && (category == null || (it.playlistId == category.playlistId && it.categoryId == category.id)) }
            .filter { "${it.kind.key}:${it.playlistId}:${it.categoryId}" !in excludeCategories }
            .drop(offset).take(limit)

    @Synchronized
    actual fun search(kind: VodKind, text: String, excludeCategories: Set<String>, limit: Int, sort: VodSort, merge: Boolean): List<VodItem> {
        val q = VodTitles.normalize(text)
        if (q.isEmpty()) return emptyList()
        return items.values.filter { it.kind == kind && VodTitles.normalize(it.name).contains(q) }
            .filter { "${it.kind.key}:${it.playlistId}:${it.categoryId}" !in excludeCategories }
            .take(limit)
    }

    @Synchronized
    actual fun byTmdb(kind: VodKind, tmdbId: String): List<VodItem> =
        items.values.filter { it.kind == kind && it.tmdbId == tmdbId }

    @Synchronized
    actual fun byTitle(kind: VodKind, title: String): List<VodItem> {
        val norm = VodTitles.normalize(title)
        if (norm.isBlank()) return emptyList()
        return items.values.filter { it.kind == kind && VodTitles.normalize(it.name) == norm }
    }

    @Synchronized
    actual fun versions(item: VodItem): List<VodItem> = listOf(item)

    @Synchronized
    actual fun categoryNames(playlistId: String, kind: VodKind): Map<String, String> =
        categories[key(playlistId, kind)].orEmpty().associate { it.id to it.name }

    @Synchronized
    actual fun item(playlistId: String, kind: VodKind, id: String): VodItem? = items[key(playlistId, kind) + "|" + id]

    @Synchronized
    actual fun learnPrefixes(playlistId: String): Set<String> {
        prefixes[playlistId] = VodTitles.learnPrefixes(items.values.asSequence().filter { it.playlistId == playlistId }.map { it.name })
        return loadPrefixes()
    }

    @Synchronized
    actual fun hasLearnedPrefixes(playlistId: String): Boolean = prefixes.containsKey(playlistId)
    @Synchronized
    actual fun loadPrefixes(): Set<String> = prefixes.values.flatten().toSet()
    @Synchronized
    actual fun cachedMatch(uid: String): VodMatch? = matches[uid]
    @Synchronized
    actual fun cacheMatch(uid: String, match: VodMatch) { matches[uid] = match }
    @Synchronized
    actual fun compact() {}

    // "Hide Non-English Titles" isn't kept in this stand-in.
    @Synchronized
    actual fun setHideForeign(on: Boolean) {}
    @Synchronized
    actual fun markForeign(playlistId: String) {}
    @Synchronized
    actual fun markForeignAll() {}
    @Synchronized
    actual fun needingLanguage(limit: Int, retryBefore: Long): List<VodItem> = emptyList()
    @Synchronized
    actual fun knownLanguages(keys: Collection<String>, retryBefore: Long): Set<String> = keys.toSet()
    @Synchronized
    actual fun saveLanguages(found: Map<String, String>): Int = 0
}
