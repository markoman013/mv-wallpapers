package com.mv.wallpapers.plugin

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject
import tv.projectivy.plugin.wallpaperprovider.api.Wallpaper
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperType

data class LibraryItem(
    val collection: String,
    val path: String,
    val title: String,
    val author: String?,
    val width: Int,
    val height: Int,
    val tags: List<String> = emptyList(),
    val bytes: Long = 0,
    val added: Long = 0
) {
    /** Width that's actually visible on a 16:9 screen after Projectivy's centre crop. */
    val visibleWidth: Int get() = if (height <= 0) width else minOf(width, height * 16 / 9)

    /** Main folder plus any ~tags. */
    val memberships: List<String> get() = listOf(collection) + tags
    val fileName: String get() = path.substringAfterLast('/')
    val thumbPath: String get() = "thumbs/" + path.removePrefix("wallpapers/")
}

data class LibraryCollection(val id: String, val name: String, val count: Int, val season: Boolean = false)

data class Library(
    val base: String,
    val page: String,
    val updated: String,
    val collections: List<LibraryCollection>,
    val items: List<LibraryItem>
)

/**
 * Reads manifest.json from the GitHub repo and hands out wallpapers from a
 * "shuffle bag": every wallpaper is shown once in random order, and only when
 * all of them have been shown is the whole library reshuffled.
 */
object WallpaperLibrary {
    private const val TAG = "MVWallpapers"
    private const val MANIFEST_FILE = "manifest.json"

    const val KEY_DISABLED = "disabled_collections"
    const val KEY_BATCH = "batch_size"
    const val KEY_MIN_WIDTH = "min_width"
    const val KEY_STRICT_SEASONS = "strict_seasons"
    private const val KEY_BAG = "bag"
    private const val KEY_SEEN = "seen"
    private const val KEY_ETAG = "manifest_etag"
    private const val KEY_BAG_FILTER = "bag_filter"
    private const val KEY_CURRENT = "current_batch"
    private const val KEY_CURRENT_AT = "current_batch_at"
    /** The current batch was picked by hand (Set as wallpaper): show it even if it isn't selected. */
    private const val KEY_PINNED = "current_pinned"
    const val MAX_SET = 50
    private const val BATCH_LIFETIME_MS = 60 * 60 * 1000L
    const val KEY_EXCLUDED = "excluded_photos"
    private const val KEY_DELETED = "deleted_photos"
    private const val KEY_RENAMED = "renamed_photos"
    private const val KEY_EMPTY = "empty_collections"
    const val KEY_HIDDEN = "hidden_collections"
    private const val KEY_FORCED = "forced_photos"
    private const val KEY_CUSTOM_DISABLED = "custom_disabled"
    private const val KEY_CUSTOM_EXCLUDED = "custom_excluded"
    private const val KEY_CUSTOM_SAVED = "custom_saved"
    private const val KEY_CUSTOM_MIN = "custom_min_width"
    private const val KEY_CUSTOM_IN = "custom_in_"

    const val DEFAULT_BATCH = 20
    val BATCH_OPTIONS = listOf(10, 20, 30, 50)
    /** Minimum visible size on a 16:9 screen, stored as width; labelled by height. */
    /** Width a photo shows at after the 16:9 crop: 1920 = 1080 px tall, 3840 = 2160 px (4K). */
    val MIN_WIDTH_OPTIONS = listOf(
        1920 to "1080p and up (everything)",
        3840 to "4K only (2160p)"
    )
    val DEFAULT_COLLECTIONS = listOf(
        LibraryCollection("animals", "Animals", 0),
        LibraryCollection("art", "Art", 0),
        LibraryCollection("sea", "Sea", 0),
        LibraryCollection("architecture", "Architecture", 0),
        LibraryCollection("landscapes", "Landscapes", 0),
        LibraryCollection("mountains", "Mountains", 0),
        LibraryCollection("space", "Space", 0),
        LibraryCollection("spring", "Spring", 0, season = true),
        LibraryCollection("summer", "Summer", 0, season = true),
        LibraryCollection("fall", "Fall", 0, season = true),
        LibraryCollection("winter", "Winter", 0, season = true)
    )

    /** Seasons everywhere in the app: spring → summer → fall → winter. */
    val SEASON_ORDER = listOf("spring", "summer", "fall", "winter")

    private val lock = Any()

    private val prefs get() = PreferencesManager.preferences

    var batchSize: Int
        get() = prefs.getInt(KEY_BATCH, DEFAULT_BATCH)
        set(value) = prefs.edit().putInt(KEY_BATCH, value).apply()

    /** Everything on GitHub is 1080p or more, so no size limit; 4K is a Select choice instead. */
    var minWidth: Int
        get() = 1920
        set(@Suppress("UNUSED_PARAMETER") value) {}

    /** true: a season-tagged photo hides while its seasons are off, even if its folder is on. */
    var strictSeasons: Boolean
        get() = prefs.getBoolean(KEY_STRICT_SEASONS, true)
        set(value) = prefs.edit().putBoolean(KEY_STRICT_SEASONS, value).apply()

    /** Single photos switched off in the photo grid (paths). */
    var excludedPhotos: Set<String>
        get() = prefs.getStringSet(KEY_EXCLUDED, null)?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_EXCLUDED, value.toSet()).apply()

    /** Photos picked by hand while their seasons are off: they show anyway (Seasonal photos setting). */
    var forcedPhotos: Set<String>
        get() = prefs.getStringSet(KEY_FORCED, null)?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_FORCED, value.toSet()).apply()

    /** Hidden only because all of its seasons are off ("Only while the season is selected"). */
    fun isSeasonBlocked(item: LibraryItem): Boolean {
        if (!strictSeasons || item.path in forcedPhotos) return false
        val mySeasons = item.memberships.filter { it in SEASON_ORDER }
        val disabled = disabledCollections
        return mySeasons.isNotEmpty() && mySeasons.all { it in disabled }
    }

    fun setPhotoEnabled(path: String, enabled: Boolean) {
        excludedPhotos = if (enabled) excludedPhotos - path else excludedPhotos + path
    }

    var disabledCollections: Set<String>
        get() = prefs.getStringSet(KEY_DISABLED, null)?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_DISABLED, value.toSet()).apply()

    /** Loads the library. Uses the network when allowed, falls back to the cached copy. */
    /**
     * Loads the library. Uses the network when allowed, falls back to the cached copy.
     * [fresh] skips GitHub's ~5-minute CDN cache (used by the Refresh button).
     * Photos deleted from this TV are hidden right away, before GitHub catches up.
     */
    fun load(context: Context, allowNetwork: Boolean = true, fresh: Boolean = false): Library? =
        loadRaw(context, allowNetwork, fresh)?.also { syncEmpty(it) }

    /**
     * Empty collections start deselected. When photos arrive they get selected; if they
     * become empty again they're deselected. Only switches collections this rule switched.
     */
    private fun syncEmpty(library: Library) {
        val wasEmpty = prefs.getStringSet(KEY_EMPTY, null)?.toSet() ?: emptySet()
        val empty = library.collections.filter { it.count == 0 }.map { it.id }.toSet()
        if (empty == wasEmpty) return
        val becameEmpty = empty - wasEmpty
        val filled = wasEmpty - empty
        disabledCollections = disabledCollections + becameEmpty - filled
        prefs.edit().putStringSet(KEY_EMPTY, empty).apply()
    }

    private fun loadRaw(context: Context, allowNetwork: Boolean, fresh: Boolean): Library? {
        val file = File(context.filesDir, MANIFEST_FILE)
        if (allowNetwork) {
            try {
                download(context, file, fresh)
            } catch (e: Exception) {
                Log.w(TAG, "Manifest download failed, using cached copy", e)
            }
        }
        if (!file.exists()) return null
        val library = try {
            parse(file.readText())
        } catch (e: Exception) {
            Log.e(TAG, "Cached manifest is invalid", e)
            return null
        }
        val deleted = prefs.getStringSet(KEY_DELETED, null) ?: emptySet()
        val renamed = prefs.getStringSet(KEY_RENAMED, null) ?: emptySet()
        if (deleted.isEmpty() && renamed.isEmpty()) return library
        val paths = library.items.map { it.path }.toSet()
        val stillListed = deleted.filter { it in paths }.toSet()
        if (stillListed.size != deleted.size) {
            // GitHub's list no longer has them: nothing left to hide.
            prefs.edit().putStringSet(KEY_DELETED, stillListed).apply()
        }
        // Moved / re-tagged on this TV: show the new version until GitHub's list has it.
        val pending = renamed.filter { e -> Rename.parse(e)?.let { it.old in paths && it.new !in paths } == true }.toSet()
        if (pending.size != renamed.size) prefs.edit().putStringSet(KEY_RENAMED, pending).apply()
        if (stillListed.isEmpty() && pending.isEmpty()) return library
        val renames = pending.mapNotNull { Rename.parse(it) }.associateBy { it.old }
        val items = library.items.filter { it.path !in stillListed }.map { item ->
            renames[item.path]?.let { r -> item.copy(collection = r.collection, path = r.new, tags = r.tags) } ?: item
        }
        val counts = items.flatMap { it.memberships }.groupingBy { it }.eachCount()
        return library.copy(
            items = items,
            collections = library.collections.map { it.copy(count = counts[it.id] ?: 0) }
        )
    }

    private data class Rename(val old: String, val new: String, val collection: String, val tags: List<String>) {
        fun encode() = listOf(old, new, collection, tags.joinToString(",")).joinToString("\t")
        companion object {
            fun parse(s: String): Rename? {
                val p = s.split("\t")
                if (p.size < 4) return null
                return Rename(p[0], p[1], p[2], p[3].split(",").filter { it.isNotEmpty() })
            }
        }
    }

    /** A photo moved or re-tagged from this TV: shown with its new place/tags right away. */
    fun markRenamed(oldPath: String, newPath: String, collection: String, tags: List<String>) {
        val all = (prefs.getStringSet(KEY_RENAMED, null) ?: emptySet()).mapNotNull { Rename.parse(it) }
        // Renamed again before GitHub caught up: keep the original old path.
        val earlier = all.firstOrNull { it.new == oldPath }
        val rest = all.filter { it !== earlier && it.old != oldPath }
        val entry = Rename(earlier?.old ?: oldPath, newPath, collection, tags)
        prefs.edit().putStringSet(KEY_RENAMED, (rest + entry).map { it.encode() }.toSet()).apply()
        // Keep it deselected if it was.
        val excluded = excludedPhotos
        if (oldPath in excluded || entry.old in excluded) excludedPhotos = excluded + newPath
    }

    /** Collections hidden from the home screen (Show: hidden lists them). */
    var hiddenCollections: Set<String>
        get() = prefs.getStringSet(KEY_HIDDEN, null)?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_HIDDEN, value.toSet()).apply()

    /** Remembers the current mixed selection so the Select button can bring it back. */
    fun saveCustom() {
        prefs.edit()
            .putStringSet(KEY_CUSTOM_DISABLED, disabledCollections)
            .putStringSet(KEY_CUSTOM_EXCLUDED, excludedPhotos)
            .putInt(KEY_CUSTOM_MIN, minWidth)
            .putBoolean(KEY_CUSTOM_SAVED, true)
            .apply()
    }

    /** Brings back the remembered custom selection; false if there is none. */
    fun restoreCustom(): Boolean {
        if (!prefs.getBoolean(KEY_CUSTOM_SAVED, false)) return false
        disabledCollections = prefs.getStringSet(KEY_CUSTOM_DISABLED, null)?.toSet() ?: emptySet()
        excludedPhotos = prefs.getStringSet(KEY_CUSTOM_EXCLUDED, null)?.toSet() ?: emptySet()
        minWidth = prefs.getInt(KEY_CUSTOM_MIN, 1920)
        return true
    }

    /** Same, inside one collection: which of its photos were deselected. */
    fun saveCustomIn(library: Library, collectionId: String) {
        val paths = itemsIn(library, collectionId).map { it.path }.toSet()
        prefs.edit().putStringSet(KEY_CUSTOM_IN + collectionId, excludedPhotos.intersect(paths)).apply()
    }

    fun restoreCustomIn(library: Library, collectionId: String): Boolean {
        val saved = prefs.getStringSet(KEY_CUSTOM_IN + collectionId, null)?.toSet() ?: return false
        val paths = itemsIn(library, collectionId).map { it.path }.toSet()
        val off = saved.intersect(paths)
        if (off.isEmpty() || off.size >= paths.size) return false
        setCollection(library, collectionId, true)
        excludedPhotos = excludedPhotos + off
        return true
    }

    /** Hide a photo deleted from this TV until GitHub's list catches up. */
    fun markDeleted(path: String) {
        val deleted = prefs.getStringSet(KEY_DELETED, null)?.toSet() ?: emptySet()
        prefs.edit().putStringSet(KEY_DELETED, deleted + path).apply()
    }

    private fun download(context: Context, file: File, fresh: Boolean) {
        val base = context.getString(R.string.manifest_url)
        val url = if (fresh) "$base?t=${System.currentTimeMillis()}" else base
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.useCaches = false
            val etag = prefs.getString(KEY_ETAG, null)
            if (etag != null && file.exists() && !fresh) {
                connection.setRequestProperty("If-None-Match", etag)
            }
            when (val status = connection.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> return
                HttpURLConnection.HTTP_OK -> {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    parse(body) // only replace the cache with a manifest that parses
                    val tmp = File(file.parentFile, "$MANIFEST_FILE.tmp")
                    tmp.writeText(body)
                    if (!tmp.renameTo(file)) throw IOException("Could not save manifest")
                    prefs.edit().putString(KEY_ETAG, connection.getHeaderField("ETag")).apply()
                }
                else -> throw IOException("Manifest request failed: HTTP $status")
            }
        } finally {
            connection.disconnect()
        }
    }

    fun parse(text: String): Library {
        val root = JSONObject(text)
        val cols = root.optJSONArray("collections") ?: JSONArray()
        val items = root.getJSONArray("items")
        return Library(
            base = root.getString("base"),
            page = root.optString("page", ""),
            updated = root.optString("updated", ""),
            collections = (0 until cols.length()).map { i ->
                val o = cols.getJSONObject(i)
                val id = o.getString("id")
                LibraryCollection(id, o.optString("name", id), o.optInt("count", 0), o.optBoolean("season", false))
            }.sortedBy { c -> if (c.season) 1000 + SEASON_ORDER.indexOf(c.id) else 0 },  // stable: folders keep their order
            items = (0 until items.length()).map { i ->
                val o = items.getJSONObject(i)
                LibraryItem(
                    collection = o.getString("c"),
                    path = o.getString("f"),
                    title = o.optString("t", ""),
                    author = if (o.has("a")) o.getString("a") else null,
                    width = o.optInt("w", 0),
                    height = o.optInt("h", 0),
                    tags = o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                    bytes = o.optLong("s", 0L),
                    added = o.optLong("d", 0L)
                )
            }
        )
    }

    /**
     * A photo shows when any of its collections (folder or ~tag) is on, except
     * that a photo tagged with seasons only shows while one of those seasons is on.
     */
    private fun isEnabled(item: LibraryItem, disabled: Set<String>, seasons: Set<String>): Boolean {
        val mine = item.memberships
        val mySeasons = mine.filter { it in seasons }
        if (strictSeasons && mySeasons.isNotEmpty() && mySeasons.all { it in disabled } && item.path !in forcedPhotos) return false
        return mine.any { it !in disabled }
    }

    /** Wallpapers that match the current settings (collections, seasons, resolution). */
    fun selectedCount(library: Library): Int = selectedItems(library).size

    /** The photos that are in the rotation right now. */
    fun selectedItems(library: Library): List<LibraryItem> {
        val disabled = disabledCollections
        val excluded = excludedPhotos
        val min = minWidth
        val seasons = library.collections.filter { it.season }.map { it.id }.toSet()
        return library.items.filter { it.path !in excluded && isEnabled(it, disabled, seasons) && it.visibleWidth >= min }
    }

    /** 4K: 2160 px tall or more after the 16:9 crop (only used for the library info). */
    fun is4k(item: LibraryItem): Boolean = minOf(item.height, item.width * 9 / 16) >= 2160

    /** Photos that belong to a collection (its folder or tagged with it). */
    fun itemsIn(library: Library, collectionId: String): List<LibraryItem> =
        library.items.filter { collectionId in it.memberships }

    /** How many photos of a collection are selected (0 when the collection is off). */
    fun onCountIn(library: Library, collectionId: String): Int {
        if (collectionId in disabledCollections) return 0
        return itemsIn(library, collectionId).count { isSelectedIn(it, collectionId) }
    }

    /** A photo is selected in a collection only if that collection is on and the photo isn't excluded. */
    fun isSelectedIn(item: LibraryItem, collectionId: String): Boolean =
        collectionId !in disabledCollections && item.path !in excludedPhotos &&
            (collectionId in SEASON_ORDER || !isSeasonBlocked(item))

    /** Collection on = it and all its photos selected; off = none of them. */
    fun setCollection(library: Library, collectionId: String, on: Boolean) {
        val paths = itemsIn(library, collectionId).map { it.path }.toSet()
        excludedPhotos = excludedPhotos - paths
        if (!on) forcedPhotos = forcedPhotos - paths
        disabledCollections = if (on) disabledCollections - collectionId else disabledCollections + collectionId
    }

    /** All collections on and every photo selected, or everything off. */
    fun setAll(library: Library, on: Boolean) {
        excludedPhotos = emptySet()
        forcedPhotos = emptySet()
        // Hidden collections stay deselected when selecting everything.
        disabledCollections = if (on) hiddenCollections + library.collections.filter { it.count == 0 }.map { it.id }
            else library.collections.map { it.id }.toSet()
    }

    /**
     * Select / unselect one photo inside a collection. Selecting a photo in a collection
     * that's off turns the collection on with only that photo selected; unselecting the
     * last selected photo turns the collection off.
     */
    fun setPhotoIn(library: Library, collectionId: String, item: LibraryItem, selected: Boolean) {
        val paths = itemsIn(library, collectionId).map { it.path }.toSet()
        if (selected) {
            // Picked by hand while its seasons are off: show it anyway.
            if (isSeasonBlocked(item)) forcedPhotos = forcedPhotos + item.path
            if (collectionId in disabledCollections) {
                disabledCollections = disabledCollections - collectionId
                excludedPhotos = excludedPhotos + (paths - item.path)
            } else {
                excludedPhotos = excludedPhotos - item.path
            }
        } else {
            excludedPhotos = excludedPhotos + item.path
            forcedPhotos = forcedPhotos - item.path
            if (paths.all { it in excludedPhotos }) setCollection(library, collectionId, false)
        }
    }

    fun isSeasonHidden(item: LibraryItem, library: Library): Boolean {
        if (!strictSeasons) return false
        val seasons = library.collections.filter { it.season }.map { it.id }.toSet()
        val mine = item.memberships.filter { it in seasons }
        val disabled = disabledCollections
        return mine.isNotEmpty() && mine.all { it in disabled }
    }

    fun url(library: Library, path: String): String = library.base + encodePath(path)

    private fun eligible(library: Library): List<LibraryItem> {
        val disabled = disabledCollections
        val min = minWidth
        val seasons = library.collections.filter { it.season }.map { it.id }.toSet()
        val excluded = excludedPhotos
        val inCollections = library.items.filter { it.path !in excluded && isEnabled(it, disabled, seasons) }
        val sized = inCollections.filter { it.visibleWidth >= min }
        // Nothing selected -> nothing to show. Resolution filter too strict -> ignore it.
        return if (sized.isNotEmpty()) sized else inCollections
    }

    /** Next batch of wallpapers for Projectivy, advancing the shuffle bag. */
    fun nextBatch(context: Context): List<Wallpaper> {
        synchronized(lock) {
            val library = load(context) ?: return emptyList()
            val byPath = eligible(library).associateBy { it.path }
            if (byPath.isEmpty()) return emptyList()

            // Switching collections or the resolution filter starts a fresh round.
            val filter = filterKey()
            if (prefs.getString(KEY_BAG_FILTER, null) != filter) {
                prefs.edit().remove(KEY_BAG).remove(KEY_SEEN).remove(KEY_CURRENT)
                    .putString(KEY_BAG_FILTER, filter).apply()
            }

            val bag = readList(prefs.getString(KEY_BAG, null)).filter { it in byPath }.toMutableList()
            val seen = readList(prefs.getString(KEY_SEEN, null)).filter { it in byPath }.toMutableSet()

            // Wallpapers added (or re-enabled) mid-cycle join the current cycle at random spots.
            val inBag = bag.toSet()
            byPath.keys.filter { it !in seen && it !in inBag }.shuffled().forEach {
                bag.add(Random.nextInt(bag.size + 1), it)
            }

            // Projectivy asks every minute (and after every settings change). Hand back the
            // same batch for an hour, so only one batch per hour counts as shown.
            val now = System.currentTimeMillis()
            val pinned = prefs.getBoolean(KEY_PINNED, false)
            val pool = if (pinned) library.items.associateBy { it.path } else byPath
            val current = readList(prefs.getString(KEY_CURRENT, null)).filter { it in pool }
            val age = now - prefs.getLong(KEY_CURRENT_AT, 0L)
            if (current.isNotEmpty() && age in 0 until BATCH_LIFETIME_MS) {
                return build(library, current.mapNotNull { pool[it] })
            }

            val count = batchSize.coerceIn(1, 100)
            val picked = mutableListOf<String>()
            while (picked.size < count) {
                if (bag.isEmpty()) {
                    if (picked.size >= byPath.size) break // library smaller than one batch
                    // Everything has been shown: start a new round with the whole library.
                    seen.clear()
                    bag.addAll(byPath.keys.filter { it !in picked }.shuffled())
                    if (bag.isEmpty()) break
                }
                val path = bag.removeAt(0)
                picked += path
                seen += path
            }

            prefs.edit()
                .putString(KEY_BAG, JSONArray(bag).toString())
                .putString(KEY_SEEN, JSONArray(seen.toList()).toString())
                .putString(KEY_CURRENT, JSONArray(picked).toString())
                .putLong(KEY_CURRENT_AT, now)
                .putBoolean(KEY_PINNED, false)
                .apply()

            return build(library, picked.mapNotNull { byPath[it] })
        }
    }

    private fun build(library: Library, items: List<LibraryItem>): List<Wallpaper> {
        val names = library.collections.associate { it.id to it.name }
        return items.map { item ->
                val pageUrl = library.page + encodePath(item.path)
                Wallpaper(
                    uri = library.base + encodePath(item.path),
                    type = WallpaperType.IMAGE,
                    // Projectivy's "About this wallpaper" screen: photo title, its collection,
                    // then any tags. The file itself is in the source link.
                    title = (listOf(item.title) + item.memberships.map { names[it] ?: it }).joinToString(" · "),
                    source = pageUrl,
                    author = item.author ?: "Unknown author"
                )
            }
    }

    /** Forget which wallpapers were shown; the next batch starts a fresh shuffle. */
    fun reset(library: Library?) {
        synchronized(lock) {
            // Build the new round right away and deal its first batch (what Projectivy gets next),
            // so "yet to show" is right straight away instead of after leaving the app.
            val bag = library?.let { eligible(it).map { i -> i.path }.shuffled() }?.toMutableList() ?: mutableListOf()
            val first = bag.take(batchSize.coerceIn(1, 100))
            bag.subList(0, first.size).clear()
            prefs.edit()
                .putString(KEY_BAG, JSONArray(bag).toString())
                .putString(KEY_SEEN, JSONArray(first).toString())
                .putString(KEY_CURRENT, JSONArray(first).toString())
                .putLong(KEY_CURRENT_AT, System.currentTimeMillis())
                .putBoolean(KEY_PINNED, false)
                .apply()
        }
    }

    private fun filterKey() = disabledCollections.sorted().joinToString(",") + "|" + minWidth + "|" + strictSeasons

    /**
     * Set as wallpaper: these photos (up to [MAX_SET]) become the batch Projectivy gets next,
     * for the usual hour, whether they're selected or not. They count as shown this round.
     */
    fun showNow(paths: List<String>) {
        if (paths.isEmpty()) return
        synchronized(lock) {
            val chosen = paths.distinct().take(MAX_SET)
            val sameRound = prefs.getString(KEY_BAG_FILTER, null) == filterKey()
            val bag = if (sameRound) readList(prefs.getString(KEY_BAG, null)) - chosen.toSet() else emptyList()
            val seen = if (sameRound) readList(prefs.getString(KEY_SEEN, null)).toSet() + chosen else chosen.toSet()
            prefs.edit()
                .putString(KEY_BAG_FILTER, filterKey())
                .putString(KEY_BAG, JSONArray(bag).toString())
                .putString(KEY_SEEN, JSONArray(seen.toList()).toString())
                .putString(KEY_CURRENT, JSONArray(chosen).toString())
                .putLong(KEY_CURRENT_AT, System.currentTimeMillis())
                .putBoolean(KEY_PINNED, true)
                .apply()
        }
    }

    /** How many wallpapers are left before the next reshuffle. */
    fun notShownYet(): Int = remainingInCycle()

    /** How many of these photos are still waiting in this round's shuffle. */
    fun notShownYetIn(paths: Set<String>): Int = readList(prefs.getString(KEY_BAG, null)).count { it in paths }

    fun remainingInCycle(): Int = readList(prefs.getString(KEY_BAG, null)).size

    private fun readList(json: String?): List<String> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { array.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun encodePath(path: String): String =
        path.split('/').joinToString("/") { Uri.encode(it) }
}
