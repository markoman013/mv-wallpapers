package com.mv.wallpapers.plugin

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView

/**
 * A collection opened right on the home screen: its photo grid and info bar replace the
 * collection cards; the top bar and filter buttons stay. Back closes it.
 */
class CollectionPane(
    private val activity: Activity,
    private val grid: GridView,
    private val metaCheck: ImageView,
    private val meta: TextView,
    private val onChanged: () -> Unit
) {
    var library: Library? = null
    var collectionId = ""
        private set
    var viewFilter = ViewFilter.ALL
    private var photos: List<LibraryItem> = emptyList()
    private val adapter = PhotoAdapter()

    /**
     * Edit multiple: OK marks photos, Hold OK edits all marked ones. The marks stay while you go
     * back and open other collections; only "Quit editing multiple" (or Done) ends it.
     */
    val bulk get() = bulkOn
    private val marked get() = bulkMarks
    var onBulkChanged: () -> Unit = {}

    fun markedCount() = marked.size
    fun allMarked() = photos.isNotEmpty() && photos.all { it.path in marked }

    fun startBulk(first: LibraryItem?) {
        bulkOn = true
        marked.clear()
        first?.let { marked += it.path }
        adapter.notifyDataSetChanged()
        onBulkChanged()
    }

    fun endBulk() {
        if (!bulkOn) return
        bulkOn = false
        marked.clear()
        adapter.notifyDataSetChanged()
        onBulkChanged()
    }

    fun allLibraryMarked() = library?.items?.let { all -> all.isNotEmpty() && all.all { it.path in marked } } ?: false

    /** At home while marking photos: a whole collection at once (all its photos, tags included). */
    fun allMarkedIn(lib: Library, id: String) = WallpaperLibrary.itemsIn(lib, id).let { l -> l.isNotEmpty() && l.all { it.path in marked } }

    fun markCollection(lib: Library, id: String, on: Boolean) {
        val paths = WallpaperLibrary.itemsIn(lib, id).map { it.path }
        if (on) marked += paths else marked -= paths.toSet()
        onBulkChanged()
    }

    /** At home: Mark all = every photo in the library. */
    fun toggleMarkLibrary() {
        val all = library?.items ?: return
        if (allLibraryMarked()) marked.clear() else all.forEach { marked += it.path }
        onBulkChanged()
    }

    fun toggleMarkAll() {
        if (allMarked()) marked.clear() else photos.forEach { marked += it.path }
        adapter.notifyDataSetChanged()
        onBulkChanged()
    }

    fun showBulkMenu() {
        val lib = library ?: return
        // Marked here and in other collections.
        val items = lib.items.filter { it.path in marked }
        PhotoActions.showBulk(activity, lib, items, collectionId,
            onSelect = { on ->
                items.forEach {
                    val c = if (collectionId.isNotEmpty() && collectionId in it.memberships) collectionId else it.collection
                    WallpaperLibrary.setPhotoIn(lib, c, it, on)
                }
                if (collectionId.isNotEmpty()) reload()
                onChanged()
            },
            onQuit = { endBulk() },
            onDone = {
                library = WallpaperLibrary.load(activity, allowNetwork = false) ?: library
                marked.clear()
                if (collectionId.isNotEmpty()) reload()
                onChanged()
                onBulkChanged()
            })
    }

    init {
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, position, _ ->
            if (bulk) {
                val item = photos.getOrNull(position) ?: return@setOnItemClickListener
                if (!marked.remove(item.path)) marked += item.path
                adapter.notifyDataSetChanged()
                onBulkChanged()
                return@setOnItemClickListener
            }
            activity.startActivity(
                Intent(activity, PreviewActivity::class.java)
                    .putExtra(EXTRA_ID, collectionId)
                    .putExtra(PreviewActivity.EXTRA_VIEW, viewFilter.name)
                    .putExtra(PreviewActivity.EXTRA_INDEX, position)
            )
        }
        grid.setOnItemLongClickListener { _, _, position, _ ->
            val lib = library ?: return@setOnItemLongClickListener true
            val item = photos.getOrNull(position) ?: return@setOnItemLongClickListener true
            if (bulk) { showBulkMenu(); return@setOnItemLongClickListener true }
            PhotoActions.show(activity, lib, item, collectionId,
                extra = listOf(MenuDialog.Row(R.drawable.ic_pill_play, "Start slideshow") {
                    activity.startActivity(Intent(activity, PreviewActivity::class.java)
                        .putExtra(PreviewActivity.EXTRA_SLIDESHOW, true)
                        .putExtra(EXTRA_ID, collectionId)
                        .putExtra(PreviewActivity.EXTRA_START, item.path))
                }),
                onSelect = { toggle(item) },
                onEditMultiple = { startBulk(item) },
                onDone = { newPath -> afterChange(newPath) })
            true
        }
        grid.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                photos.getOrNull(position)?.let { showMeta(it) }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun toggle(item: LibraryItem) {
        val lib = library ?: return
        WallpaperLibrary.setPhotoIn(lib, collectionId, item, !WallpaperLibrary.isSelectedIn(item, collectionId))
        if (viewFilter != ViewFilter.ALL) reload() else adapter.notifyDataSetChanged()
        showMeta(item)
        onChanged()
    }

    /** After a move, tag edit or delete: reload from the saved list and stay near the same spot. */
    private fun afterChange(newPath: String?) {
        library = WallpaperLibrary.load(activity, allowNetwork = false) ?: return
        val pos = grid.selectedItemPosition
        reload()
        val at = photos.indexOfFirst { it.path == newPath }
        grid.setSelection(if (at >= 0) at else pos.coerceIn(0, maxOf(0, photos.size - 1)))
        onChanged()
    }

    /** Back home: no collection is open (marks from Edit multiple stay). */
    fun leave() { collectionId = "" }

    fun open(lib: Library, id: String) {
        library = lib
        collectionId = id
        viewFilter = ViewFilter.ALL
        reload()
        grid.setSelection(0)
    }

    fun name(): String = library?.collections?.firstOrNull { it.id == collectionId }?.name ?: collectionId

    /** "80 of 86 selected · 12 yet to show this round" for this collection. */
    fun counts(): String {
        val lib = library ?: return ""
        val all = WallpaperLibrary.itemsIn(lib, collectionId)
        val on = WallpaperLibrary.onCountIn(lib, collectionId)
        val waiting = WallpaperLibrary.notShownYetIn(all.map { it.path }.toSet())
        return "$on of ${all.size} selected  ·  $waiting yet to show this round"
    }

    fun size(): Int = library?.let { WallpaperLibrary.itemsIn(it, collectionId).size } ?: 0

    /** Bottom bar: "80 selected · 12 yet to show this round" (the total is next to the name). */
    fun barCounts(): String {
        val lib = library ?: return ""
        val all = WallpaperLibrary.itemsIn(lib, collectionId)
        val on = WallpaperLibrary.onCountIn(lib, collectionId)
        if (on == 0) return "0 selected"
        val waiting = WallpaperLibrary.notShownYetIn(all.map { it.path }.toSet())
        return "$on selected  ·  $waiting yet to show this round"
    }

    fun selectLabel(): String {
        val lib = library ?: return "Select: all"
        val all = WallpaperLibrary.itemsIn(lib, collectionId).size
        val on = WallpaperLibrary.onCountIn(lib, collectionId)
        return when {
            on == 0 -> "Select: none"
            on >= all -> "Select: all"
            else -> "Select: custom"
        }
    }

    /** all → none → custom (last mixed choice, remembered) → all */
    fun toggleAll() {
        val lib = library ?: return
        val all = WallpaperLibrary.itemsIn(lib, collectionId).size
        val on = WallpaperLibrary.onCountIn(lib, collectionId)
        when {
            on in 1 until all -> {
                WallpaperLibrary.saveCustomIn(lib, collectionId)
                WallpaperLibrary.setCollection(lib, collectionId, true)
            }
            on >= all -> WallpaperLibrary.setCollection(lib, collectionId, false)
            else -> if (!WallpaperLibrary.restoreCustomIn(lib, collectionId)) WallpaperLibrary.setCollection(lib, collectionId, true)
        }
        reload()
        onChanged()
    }

    fun reload() {
        val lib = library
        photos = if (lib == null) emptyList() else filterPhotos(WallpaperLibrary.itemsIn(lib, collectionId), viewFilter, collectionId)
        adapter.notifyDataSetChanged()
        if (photos.isEmpty()) {
            metaCheck.visibility = View.INVISIBLE
            Meta.show(meta, when (viewFilter) {
                ViewFilter.ALL, ViewFilter.HIDDEN -> "No photos in this collection yet."
                ViewFilter.SELECTED -> "No selected photos here."
                ViewFilter.NOT_SELECTED -> "Every photo here is selected."
            })
        } else {
            val pos = grid.selectedItemPosition.takeIf { it in photos.indices } ?: 0
            showMeta(photos[pos])
        }
    }

    /** Info for the selected photo (called when the grid gets focus). */
    fun showSelected() {
        photos.getOrNull(grid.selectedItemPosition.takeIf { it >= 0 } ?: 0)?.let { showMeta(it) }
    }

    private fun showMeta(item: LibraryItem) {
        if (!grid.hasFocus()) return  // the home screen shows the collection totals instead
        val lib = library ?: return
        Meta.show(meta, Meta.describe(activity, item, lib))
        metaCheck.setImageResource(Meta.checkIcon(item, collectionId))
        metaCheck.visibility = View.VISIBLE
    }

    private inner class PhotoAdapter : BaseAdapter() {
        override fun getCount() = photos.size
        override fun getItem(position: Int): Any? = photos.getOrNull(position)
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: activity.layoutInflater.inflate(R.layout.item_photo, parent, false).also {
                val photo = it.findViewById<View>(R.id.photo)
                photo.outlineProvider = ViewOutlineProvider.BACKGROUND
                photo.clipToOutline = true
            }
            val image = view.findViewById<ImageView>(R.id.photo)
            val check = view.findViewById<ImageView>(R.id.check)
            val item = photos[position]
            // Badges (only while selected): real folder when tagged in here, other collections, season.
            val seasonIds = WallpaperLibrary.SEASON_ORDER.toSet()
            val inSeason = collectionId in seasonIds
            val lib = library
            val on = WallpaperLibrary.isSelectedIn(item, collectionId)
            // Would be selected, but its seasons are off: dim it and show a crossed-out season instead.
            val seasonOff = !on && !inSeason && collectionId !in WallpaperLibrary.disabledCollections &&
                item.path !in WallpaperLibrary.excludedPhotos && WallpaperLibrary.isSeasonBlocked(item)
            val otherTags = item.memberships.filter { it != collectionId && it != item.collection && it !in seasonIds }
            view.findViewById<View>(R.id.badgeFolder).visibility = if (on && item.collection != collectionId) View.VISIBLE else View.GONE
            view.findViewById<View>(R.id.badgeTag).visibility = if (on && otherTags.isNotEmpty()) View.VISIBLE else View.GONE
            view.findViewById<View>(R.id.badgeSeason).visibility =
                if (on && !inSeason && item.memberships.any { it in seasonIds }) View.VISIBLE else View.GONE
            view.findViewById<View>(R.id.seasonOff).visibility = if (seasonOff) View.VISIBLE else View.GONE
            check.visibility = if (on) View.VISIBLE else View.GONE
            view.findViewById<ImageView>(R.id.mark).apply {
                visibility = if (bulk) View.VISIBLE else View.GONE
                setImageResource(if (item.path in marked) R.drawable.mark_on else R.drawable.mark_off)
            }
            image.alpha = if (on) 1f else 0.35f
            if (lib != null) {
                ImageLoader.load(
                    image, WallpaperLibrary.url(lib, item.thumbPath), 480,
                    fallbackUrl = WallpaperLibrary.url(lib, item.path)
                )
            }
            return view
        }
    }

    companion object {
        const val EXTRA_ID = "collection_id"
        private var bulkOn = false
        private val bulkMarks = linkedSetOf<String>()

        /** Photos for the grid and the preview: filtered by the Show button, sorted by Sort. */
        fun filterPhotos(all: List<LibraryItem>, filter: ViewFilter, collectionId: String): List<LibraryItem> {
            val sized = all
            val filtered = when (filter) {
                ViewFilter.ALL, ViewFilter.HIDDEN -> sized
                ViewFilter.SELECTED -> sized.filter { WallpaperLibrary.isSelectedIn(it, collectionId) }
                ViewFilter.NOT_SELECTED -> sized.filter { !WallpaperLibrary.isSelectedIn(it, collectionId) }
            }
            return when (SortOrder.photos) {
                SortOrder.NEWEST, SortOrder.DEFAULT -> filtered.sortedWith(compareByDescending<LibraryItem> { it.added }.thenBy { it.title })
                SortOrder.NAME -> filtered.sortedBy { it.title.lowercase() }
            }
        }
    }
}
