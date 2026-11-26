package com.mv.wallpapers.plugin

import android.app.Activity
import android.os.Build
import android.view.View
import android.window.OnBackInvokedDispatcher
import android.os.Bundle
import android.view.KeyEvent
import android.widget.ImageView
import android.widget.TextView

/**
 * Full-screen preview with details. Left/right: previous/next. OK: switch on/off.
 * Slideshow mode: random order, a new photo every minute, a smaller bar; OK hides the bar,
 * and the Hold-OK menu pauses it while it's open.
 */
class PreviewActivity : Activity() {

    private var library: Library? = null
    private var photos: List<LibraryItem> = emptyList()
    private var index = 0
    private var collectionId = ""
    private var filter = ViewFilter.ALL

    private lateinit var image: ImageView
    private lateinit var meta: TextView
    private lateinit var metaCheck: ImageView
    private lateinit var overlay: View
    private lateinit var counter: TextView
    private var uiHidden = false
    private var slideshow = false
    private var paused = false
    private var waitedForMenu = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (paused) return
            // A menu is open: wait, then give the photo a full minute again after it closes.
            if (Overlay.isOpen(this@PreviewActivity)) { waitedForMenu = true; handler.postDelayed(this, 1000); return }
            if (waitedForMenu) { waitedForMenu = false; handler.postDelayed(this, SLIDE_MS); return }
            if (photos.isNotEmpty()) { index = (index + 1) % photos.size; show() }
            handler.postDelayed(this, SLIDE_MS)
        }
    }

    /** The collection a photo's select/deselect applies to (a home slideshow uses its own folder). */
    private fun cid(item: LibraryItem) = collectionId.ifEmpty { item.collection }

    private fun togglePause() {
        paused = !paused
        restartTimer()
        show()
    }

    private fun restartTimer() {
        if (!slideshow) return
        handler.removeCallbacks(tick)
        if (!paused) handler.postDelayed(tick, SLIDE_MS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        PreferencesManager.init(this)
        ColorTheme.apply(this)
        super.onCreate(savedInstanceState)
        PreferencesManager.init(this)
        ImageLoader.init(this)
        setContentView(R.layout.activity_preview)
        image = findViewById(R.id.image)
        meta = findViewById(R.id.meta)
        metaCheck = findViewById(R.id.metaCheck)
        overlay = findViewById(R.id.overlay)
        counter = findViewById(R.id.counter)
        (overlay as BlurBar).apply {
            cornerRadiusDp = 20f
            blurBehind(image)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { handleBack() }
        }

        val lib = WallpaperLibrary.load(this, allowNetwork = false)
        library = lib
        val id = intent.getStringExtra(CollectionPane.EXTRA_ID) ?: ""
        collectionId = id
        filter = runCatching { ViewFilter.valueOf(intent.getStringExtra(EXTRA_VIEW) ?: "") }
            .getOrDefault(ViewFilter.ALL)
        slideshow = intent.getBooleanExtra(EXTRA_SLIDESHOW, false)
        photos = when {
            lib == null -> emptyList()
            slideshow -> {
                // Selected photos (all of them when nothing is selected), in random order.
                val pool = if (id.isEmpty()) lib.items else WallpaperLibrary.itemsIn(lib, id)
                val on = if (id.isEmpty()) WallpaperLibrary.selectedItems(lib) else pool.filter { WallpaperLibrary.isSelectedIn(it, id) }
                val list = on.ifEmpty { pool }.shuffled()
                // Started from a photo: that one first.
                val start = intent.getStringExtra(EXTRA_START)
                val first = list.firstOrNull { it.path == start } ?: pool.firstOrNull { it.path == start }
                if (first != null) listOf(first) + (list - first) else list
            }
            else -> CollectionPane.filterPhotos(WallpaperLibrary.itemsIn(lib, id), filter, id)
        }
        index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, maxOf(0, photos.size - 1))
        if (slideshow) {
            if (photos.isEmpty()) return finish()
            slideshowUi()
        }
        show()
    }

    private fun slideshowUi() {
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        for (v in listOf(R.id.metaCheck, R.id.meta2, R.id.counter)) findViewById<View>(v).visibility = View.GONE
        (meta.layoutParams as android.view.ViewGroup.MarginLayoutParams).bottomMargin = 0
        meta.requestLayout()
        restartTimer()
    }

    /** From the full-screen preview: this photo first, then the others of this view in random order. */
    private fun startSlideshow() {
        val cur = photos.getOrNull(index) ?: return
        photos = listOf(cur) + (photos - cur).shuffled()
        index = 0
        slideshow = true
        paused = false
        slideshowUi()
        show()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    /** New photo: load the image (keeping the old one on screen until it's ready) and its info. */
    private fun show() {
        val lib = library ?: return
        val item = photos.getOrNull(index) ?: return
        val width = resources.displayMetrics.widthPixels.coerceAtLeast(1280)
        ImageLoader.load(image, WallpaperLibrary.url(lib, item.path), width, memoryCache = false, keepCurrent = true)
        showInfo()
    }

    /** Only the info bar (selecting doesn't reload the photo, so no black flash). */
    private fun showInfo() {
        val lib = library ?: return
        val item = photos.getOrNull(index) ?: return
        if (slideshow) {
            meta.text = Meta.compact(this, item, lib)
            meta.isSelected = true
            // One line: legend and counter on the right.
            val legend = android.text.SpannableStringBuilder(if (paused) "Paused  ·  OK: play  ·  Hold OK: options" else "OK: pause  ·  Hold OK: options")
            legend.append("     ")
            val at = legend.length
            legend.append("${index + 1} / ${photos.size}")
            legend.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), at, legend.length, 0)
            legend.setSpan(android.text.style.ForegroundColorSpan(getColor(R.color.text_primary)), at, legend.length, 0)
            findViewById<TextView>(R.id.hint).text = legend
            return
        }
        Meta.show(meta, Meta.describe(this, item, lib))
        counter.text = "${index + 1} / ${photos.size}"
        metaCheck.setImageResource(Meta.checkIcon(item, collectionId))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // A menu or pop-up is open: don't switch photos, but let Android move focus inside it.
        if (Overlay.isOpen(this) && keyCode != KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (slideshow && photos.isNotEmpty()) { index = (index + 1) % photos.size; show(); restartTimer() }
                else if (index < photos.size - 1) { index++; show() }
            KeyEvent.KEYCODE_DPAD_LEFT -> if (slideshow && photos.isNotEmpty()) { index = (index - 1 + photos.size) % photos.size; show(); restartTimer() }
                else if (index > 0) { index--; show() }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                event.startTracking() // lets a long press open the menu
                return true
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (Overlay.isOpen(this) && keyCode != KeyEvent.KEYCODE_BACK) return super.onKeyUp(keyCode, event)
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event.isTracking && !event.isCanceled) { if (slideshow) togglePause() else toggleSelected() }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (Overlay.isOpen(this)) return super.onKeyLongPress(keyCode, event)
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            showActions()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    private fun toggleSelected() {
        val item = photos.getOrNull(index) ?: return
        val lib = library ?: return
        WallpaperLibrary.setPhotoIn(lib, cid(item), item, !WallpaperLibrary.isSelectedIn(item, cid(item)))
        showInfo()
    }

    private fun showActions() {
        val lib = library ?: return
        val item = photos.getOrNull(index) ?: return
        PhotoActions.show(
            this, lib, item, cid(item),
            extra = listOfNotNull(
                MenuDialog.Row(if (uiHidden) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen,
                    if (uiHidden) "Show UI" else "Hide UI") { setUiHidden(!uiHidden) },
                // One button: start, then pause / resume.
                when {
                    !slideshow -> MenuDialog.Row(R.drawable.ic_pill_play, "Start slideshow") { startSlideshow() }
                    paused -> MenuDialog.Row(R.drawable.ic_pill_play, "Resume slideshow") { togglePause() }
                    else -> MenuDialog.Row(R.drawable.ic_schedule, "Pause slideshow") { togglePause() }
                }
            ),
            onSelect = { toggleSelected() },
            onDone = { newPath -> afterChange(newPath) }
        )
    }

    /** After a move, tag edit or delete: reload the list and stay on the same photo if it's still here. */
    private fun afterChange(newPath: String?) {
        val lib = WallpaperLibrary.load(this, allowNetwork = false) ?: return finish()
        library = lib
        val cur = photos.getOrNull(index)?.path
        val byPath = lib.items.associateBy { it.path }
        // Slideshow: same order; the changed photo is replaced by its new version (or dropped).
        photos = if (slideshow) photos.mapNotNull { p -> if (p.path == cur) newPath?.let { byPath[it] } else byPath[p.path] }
            else CollectionPane.filterPhotos(WallpaperLibrary.itemsIn(lib, collectionId), filter, collectionId)
        if (photos.isEmpty()) return finish()
        index = photos.indexOfFirst { it.path == newPath }.takeIf { it >= 0 } ?: index.coerceAtMost(photos.size - 1)
        show()
    }

    /** Hide the info bar to see only the photo. Back (or the menu) brings it back. */
    private fun setUiHidden(hidden: Boolean) {
        uiHidden = hidden
        overlay.visibility = if (hidden) View.GONE else View.VISIBLE
    }

    private fun handleBack() {
        if (Overlay.back(this)) return
        if (uiHidden) setUiHidden(false) else finish()
    }

    @Deprecated("Used on Android versions without predictive back")
    override fun onBackPressed() {
        handleBack()
    }

    companion object {
        const val EXTRA_INDEX = "index"
        const val EXTRA_VIEW = "view"
        const val EXTRA_SLIDESHOW = "slideshow"
        const val EXTRA_START = "start"
        /** A new photo every minute. */
        private const val SLIDE_MS = 60_000L
    }
}
