package com.mv.wallpapers.plugin

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.view.ViewOutlineProvider

/** Home screen: collection cards, totals, options and update check. Opened from Projectivy. */
class SettingsActivity : Activity() {

    private var library: Library? = null
    private var collections: List<LibraryCollection> = WallpaperLibrary.DEFAULT_COLLECTIONS
    private var settingsChanged = false
    private var view = ViewFilter.ALL
    private var currentTab = Tab.COLLECTIONS
    private lateinit var tabCollections: Button
    private lateinit var tabSettings: Button
    private lateinit var settingsPage: SettingsPage
    private var shown: List<LibraryCollection> = emptyList()
    private var covers: Map<String, LibraryItem> = emptyMap()

    private lateinit var grid: GridView
    private lateinit var total: TextView
    private lateinit var viewButton: Button
    private lateinit var selectButton: Button
    private lateinit var updateButton: Button
    private var update: UpdateChecker.Update? = null
    private val adapter = CardAdapter()
    private lateinit var pane: CollectionPane
    private lateinit var sortButton: Button
    private var openCollectionId: String? = null
    private var cardPosition = 0
    /** True while a collection opens/closes: focus jumping around then must not switch tabs. */
    private var swapping = false

    override fun onCreate(savedInstanceState: Bundle?) {
        PreferencesManager.init(this)
        ColorTheme.apply(this)
        super.onCreate(savedInstanceState)
        PreferencesManager.init(this)
        ImageLoader.init(this)
        setContentView(R.layout.activity_home)
        UpdateChecker.cleanup(this)

        findViewById<TextView>(R.id.title).text = "v${BuildConfig.VERSION_NAME}"
        total = findViewById(R.id.total)
        grid = findViewById(R.id.grid)
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, position, _ ->
            val c = shown.getOrNull(position) ?: return@setOnItemClickListener
            if (isHeader(c)) { cycleSeasons(); return@setOnItemClickListener }
            if (isBlank(c)) return@setOnItemClickListener
            if (cardBulk) {
                if (!markedCards.remove(c.id)) markedCards += c.id
                adapter.notifyDataSetChanged()
                updateBulkUi()
                return@setOnItemClickListener
            }
            cardPosition = position
            openCollection(c.id)
        }
        pane = CollectionPane(this, findViewById(R.id.photoGrid), findViewById(R.id.metaCheck), findViewById(R.id.meta)) {
            settingsChanged = true
            val fresh = pane.library
            if (fresh != null && fresh !== library) applyLibrary(fresh) else refresh()
            if (pane.bulk) updateBulkUi()
        }
        // The bottom bar shows the focused collection, like it shows the photo inside one.
        grid.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, position: Int, id: Long) = showCardInfo(position)
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        grid.setOnItemLongClickListener { _, _, position, _ ->
            val c = shown.getOrNull(position)
            if (c != null && !isHeader(c) && !isBlank(c)) { if (cardBulk) showCardBulkMenu() else showCollectionMenu(position) }
            true
        }
        // The seasons header row and the blank cells before it: skip blanks, the header row is one stop.
        (grid as FocusGridView).cellKind = { pos ->
            val c = shown.getOrNull(pos)
            when {
                c == null -> FocusGridView.Cell.NORMAL
                isBlank(c) -> FocusGridView.Cell.SKIP
                isHeader(c) -> FocusGridView.Cell.GROUP
                else -> FocusGridView.Cell.NORMAL
            }
        }
        // One button: shows the current state, pressing switches between all and none.
        updateButton = findViewById(R.id.update)
        updateButton.setOnClickListener { installUpdate() }
        selectButton = findViewById(R.id.select)
        selectButton.setOnClickListener {
            if (openCollectionId != null) {
                pane.toggleAll()
                return@setOnClickListener
            }
            val lib = library ?: return@setOnClickListener
            // all → none → custom (the last mixed selection, always remembered) → all
            when (selectState(lib)) {
                SelectState.ALL -> WallpaperLibrary.setAll(lib, false)
                SelectState.NONE -> if (!WallpaperLibrary.restoreCustom()) WallpaperLibrary.setAll(lib, true)
                SelectState.SOME -> {
                    WallpaperLibrary.saveCustom()
                    WallpaperLibrary.setAll(lib, true)
                }
            }
            settingsChanged = true
            refresh()
        }
        // Tabs: Collections (seasons below them) and Settings, a page right here under the tabs.
        tabCollections = findViewById(R.id.tabCollections)
        tabSettings = findViewById(R.id.settings)
        settingsPage = SettingsPage(this, findViewById(R.id.settingsPage)) {
            settingsChanged = true
            applyLibrary(WallpaperLibrary.load(this, allowNetwork = false))
        }
        tabCollections.setOnClickListener { closeCollection(); showTab(Tab.COLLECTIONS) }
        tabSettings.setOnClickListener { closeCollection(); showTab(Tab.SETTINGS) }
        tabCollections.isActivated = true
        // Highlighting a tab opens it (like the Google TV home screen); OK isn't needed.
        for ((button, tab) in listOf(tabCollections to Tab.COLLECTIONS, tabSettings to Tab.SETTINGS)) {
            button.setOnFocusChangeListener { _, focused ->
                if (focused && currentTab != tab && !swapping) {
                    closeCollection()
                    showTab(tab)
                    // The new page may grab focus while it loads: always stay on the tab.
                    button.post { if (!button.hasFocus()) button.requestFocus() }
                }
            }
        }
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, _ -> updateBar() }
        setUpBlur()
        findViewById<FocusGridView>(R.id.photoGrid).scrollbarOnlyWhenScrolling = true
        alignBarToGrid(grid)
        alignBarToGrid(findViewById(R.id.photoGrid))
        // Grow buttons from a whole-pixel centre, so scaled text stays sharp at any width.
        for (id in intArrayOf(R.id.select, R.id.view, R.id.sort, R.id.update, R.id.tabCollections, R.id.settings)) {
            findViewById<View>(id).addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                v.pivotX = (v.width / 2).toFloat()
                v.pivotY = (v.height / 2).toFloat()
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) { handleBack() }
        }

        sortButton = findViewById(R.id.sort)
        sortButton.text = homeSort().label
        sortButton.setOnClickListener {
            if (openCollectionId != null) {
                SortOrder.photos = SortOrder.nextPhotos()
                sortButton.text = SortOrder.photos.label
                pane.reload()
                return@setOnClickListener
            }
            SortOrder.collections = SortOrder.nextCollections()
            sortButton.text = homeSort().label
            library?.let { applyLibrary(it) }
        }
        pane.onBulkChanged = { updateBulkUi() }
        findViewById<Button>(R.id.slideshow).setOnClickListener {
            startActivity(android.content.Intent(this, PreviewActivity::class.java)
                .putExtra(PreviewActivity.EXTRA_SLIDESHOW, true)
                .putExtra(CollectionPane.EXTRA_ID, openCollectionId ?: ""))
        }
        findViewById<Button>(R.id.bulkDone).setOnClickListener { endBulk() }
        findViewById<Button>(R.id.bulkMark).setOnClickListener {
            if (pane.bulk && openCollectionId == null) pane.toggleMarkLibrary()
            else if (pane.bulk) pane.toggleMarkAll() else {
                val all = shown.filter { !isHeader(it) && !isBlank(it) }.map { it.id }
                if (markedCards.containsAll(all)) markedCards.clear() else markedCards += all
                adapter.notifyDataSetChanged()
                updateBulkUi()
            }
        }
        findViewById<Button>(R.id.bulkActions).setOnClickListener {
            if (pane.bulk) pane.showBulkMenu() else showCardBulkMenu()
        }
        viewButton = findViewById(R.id.view)
        viewButton.setOnClickListener {
            if (openCollectionId != null) {
                pane.viewFilter = pane.viewFilter.next()
                viewButton.text = pane.viewFilter.label
                pane.reload()
                return@setOnClickListener
            }
            view = view.next(home = true)
            viewButton.text = view.label
            refresh()
        }
        grid.requestFocus()

        if (!isInstalled(PROJECTIVY_PACKAGE_ID)) {
            Toast.makeText(this, R.string.projectivy_not_installed, Toast.LENGTH_LONG).show()
        }

        applyLibrary(WallpaperLibrary.load(this, allowNetwork = false))
        // After a theme change: once the screen is laid out, move to the Settings tab the same way
        // highlighting it does (its focus listener opens the tab).
        if (reopenSettings) {
            reopenSettings = false
            window.decorView.postDelayed({ if (!isFinishing) tabSettings.requestFocus() }, 300)
        }
        Thread {
            val fresh = WallpaperLibrary.load(applicationContext, allowNetwork = true)
            val newer = UpdateChecker.check()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (fresh != null) applyLibrary(fresh)
                // Only shown when there is an update.
                update = newer
                if (newer != null) {
                    updateButton.text = "Update to v${newer.version}"
                    updateButton.visibility = View.VISIBLE
                }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        // Back from the full-screen preview: selections or deletions may have changed.
        if (library != null) applyLibrary(WallpaperLibrary.load(this, allowNetwork = false)) else refresh()
        if (openCollectionId != null) {
            pane.library = library
            pane.reload()
            // The name line carries the count, so it has to follow a delete or move made in full screen.
            findViewById<TextView>(R.id.collectionName).text = pane.name()
        }
    }

    /** Open a collection right here: its photos replace the cards; filters stay. */
    private fun openCollection(id: String) {
        val lib = library ?: return
        if (cardBulk) endBulk()
        swapping = true
        openCollectionId = id
        pane.open(lib, id)
        grid.visibility = View.GONE
        findViewById<TextView>(R.id.barHint).text = "OK: full screen · Hold OK: options"
        findViewById<FocusGridView>(R.id.photoGrid).resetScrollbar()
        findViewById<View>(R.id.photoGrid).visibility = View.VISIBLE
        findViewById<TextView>(R.id.collectionName).apply {
            text = pane.name()
            visibility = View.VISIBLE
        }
        viewButton.text = pane.viewFilter.label
        sortButton.text = SortOrder.photos.label
        refresh()
        findViewById<View>(R.id.photoGrid).requestFocus()
        swapping = false
        if (pane.bulk) updateBulkUi()
    }

    private fun closeCollection() {
        if (openCollectionId == null) return
        pane.leave()
        swapping = true
        openCollectionId = null
        findViewById<View>(R.id.photoGrid).visibility = View.GONE
        findViewById<View>(R.id.collectionName).visibility = View.GONE
        grid.visibility = View.VISIBLE
        findViewById<TextView>(R.id.barHint).text = HOME_HINT
        viewButton.text = view.label
        sortButton.text = homeSort().label
        refresh()
        grid.setSelection(cardPosition.coerceIn(0, maxOf(0, shown.size - 1)))
        grid.requestFocus()
        swapping = false
        if (pane.bulk) updateBulkUi()
    }

    // No "refresh now" broadcast to Projectivy on leaving: it made Projectivy drop the
    // current wallpaper and show its default while loading. Projectivy asks the plugin
    // again within a minute anyway (1-minute cache), so changes still apply quickly.

    private var shownOnce = false

    private fun applyLibrary(lib: Library?) {
        library = lib
        if (lib != null && lib.collections.isNotEmpty()) collections = sortCollections(lib)
        // First fill: keep the cards hidden until they've settled (the seasons row flashed mid-screen).
        val first = !shownOnce && lib != null && collections.isNotEmpty()
        if (first) { shownOnce = true; grid.alpha = 0f }
        refresh()
        if (first) grid.postDelayed({ grid.animate().alpha(1f).setDuration(120).start() }, 220)
    }

    /** Sort button: by name, or by the newest photo in each collection. Empty ones last. */
    private fun sortCollections(lib: Library): List<LibraryCollection> {
        val newest = lib.collections.associate { c ->
            c.id to (WallpaperLibrary.itemsIn(lib, c.id).maxOfOrNull { it.added } ?: 0L)
        }
        // Each tab has its own sort: Collections A–Z / new, Seasons default (spring → winter) / new.
        val (folders, seasons) = lib.collections.partition { !it.season }
        return sortIn(folders, SortOrder.collections, newest) + sortIn(seasons, SortOrder.DEFAULT, newest)
    }

    private fun homeSort(): SortOrder = SortOrder.collections

    private fun sortIn(list: List<LibraryCollection>, order: SortOrder, newest: Map<String, Long>): List<LibraryCollection> {
        return when (order) {
            SortOrder.DEFAULT -> list.sortedWith(compareBy<LibraryCollection>({ it.count == 0 }, { WallpaperLibrary.SEASON_ORDER.indexOf(it.id) }))
            SortOrder.NAME -> list.sortedWith(compareBy<LibraryCollection>({ it.count == 0 }, { it.name.lowercase() }))
            SortOrder.NEWEST -> list.sortedWith(
                compareBy<LibraryCollection>({ it.count == 0 }).thenByDescending { newest[it.id] ?: 0L }.thenBy { it.name }
            )
        }
    }

    private fun refresh() {
        val lib = library
        val selected = lib?.let { WallpaperLibrary.selectedCount(it) } ?: 0
        total.text = when {
            lib == null -> "Loading library…"
            selected == 0 -> "0 of ${lib.items.size} selected  ·  the wallpaper will be reset to default"
            else -> "$selected of ${lib.items.size} selected  ·  " +
                "${WallpaperLibrary.notShownYet()} yet to show this round"
        }
        covers = lib?.let { pickCovers(it) } ?: emptyMap()
        val disabled = WallpaperLibrary.disabledCollections
        val hidden = WallpaperLibrary.hiddenCollections
        // Collections first (empty ones last), then a header row and the seasons in year order.
        fun filtered(list: List<LibraryCollection>): List<LibraryCollection> {
            val visible = list.filter { it.id !in hidden }
            return when (view) {
                ViewFilter.ALL -> visible
                ViewFilter.SELECTED -> visible.filter { it.id !in disabled }
                ViewFilter.NOT_SELECTED -> visible.filter { c ->
                    c.id in disabled || (lib != null && onOf(lib, c) < countOf(lib, c))
                }
                ViewFilter.HIDDEN -> list.filter { it.id in hidden }
            }
        }
        val folders = filtered(collections.filter { !it.season })
        val seasons = filtered(collections.filter { it.season })
        shown = if (seasons.isEmpty()) folders else {
            val cols = grid.numColumns.coerceAtLeast(1)
            val blanks = (cols - folders.size % cols) % cols
            folders + List(blanks) { BLANK } + List(cols) { HEADER } + seasons
        }
        if (openCollectionId != null) {
            total.text = pane.counts()
            if (pane.bulk) bulkText()
            selectButton.text = pane.selectLabel()
            adapter.notifyDataSetChanged()
            updateBar()
            return
        }
        updateBar()
        if (cardBulk || pane.bulk) bulkText()
        selectButton.text = if (lib == null) "Select: all" else when (selectState(lib)) {
            SelectState.ALL -> "Select: all"
            SelectState.SOME -> "Select: custom"
            SelectState.NONE -> "Select: none"
        }
        adapter.notifyDataSetChanged()
    }

    /**
     * A different cover for every collection. Folder collections pick from their own
     * folder first, seasons after them; a photo is only reused when a collection has
     * nothing else left.
     */
    private fun pickCovers(lib: Library): Map<String, LibraryItem> {
        val excluded = WallpaperLibrary.excludedPhotos
        val used = mutableSetOf<String>()
        val result = mutableMapOf<String, LibraryItem>()
        for (c in collections.sortedBy { it.season }) {
            val inC = WallpaperLibrary.itemsIn(lib, c.id)
            if (inC.isEmpty()) continue
            val ordered = inC.sortedWith(compareBy({ it.path in excluded }, { it.collection != c.id }))
            val pick = ordered.firstOrNull { it.path !in used } ?: ordered.first()
            used += pick.path
            result[c.id] = pick
        }
        return result
    }

    private fun selectState(lib: Library): SelectState {
        if (WallpaperLibrary.selectedCount(lib) == 0) return SelectState.NONE
        val skip = WallpaperLibrary.hiddenCollections + lib.collections.filter { it.count == 0 }.map { it.id }
        if ((WallpaperLibrary.disabledCollections - skip).isNotEmpty()) return SelectState.SOME
        val existing = lib.items.map { it.path }.toSet()
        val off = WallpaperLibrary.excludedPhotos.intersect(existing)
        return if (off.isEmpty()) SelectState.ALL else SelectState.SOME
    }

    private enum class Tab { COLLECTIONS, SETTINGS }

    private fun showTab(tab: Tab) {
        currentTab = tab
        tabCollections.isActivated = tab == Tab.COLLECTIONS
        tabSettings.isActivated = tab == Tab.SETTINGS
        val settingsOn = tab == Tab.SETTINGS
        findViewById<View>(R.id.collectionsPage).visibility = if (settingsOn) View.GONE else View.VISIBLE
        findViewById<View>(R.id.settingsPage).visibility = if (settingsOn) View.VISIBLE else View.GONE
        if (!settingsOn) {
            if (openCollectionId == null) sortButton.text = homeSort().label
            refresh()
        }
    }

    /** Back: out of the settings choices, then back to Collections, then close. */
    private fun handleBack() {
        if (Overlay.back(this)) return
        if (cardBulk) { endBulk(); return }
        // Inside a collection: first to the first photo, then back home.
        if (openCollectionId != null) {
            val photos = findViewById<FocusGridView>(R.id.photoGrid)
            val inTopBar = findViewById<View>(R.id.topBar).hasFocus()
            if (!inTopBar && photos.count > 0 && !(photos.hasFocus() && photos.selectedItemPosition == 0)) {
                photos.setSelection(0)
                photos.requestFocus()
            } else {
                closeCollection()
            }
            return
        }
        if (currentTab == Tab.SETTINGS && settingsPage.handleBack()) return
        // Any tab: 1st Back to the top bar (this tab), 2nd to the Collections tab, then close.
        val tabButton = when (currentTab) {
            Tab.COLLECTIONS -> tabCollections
            Tab.SETTINGS -> tabSettings
        }
        if (!tabButton.hasFocus()) {
            tabButton.requestFocus()
            return
        }
        if (currentTab != Tab.COLLECTIONS) {
            showTab(Tab.COLLECTIONS)
            tabCollections.requestFocus()
            return
        }
        finish()
    }

    @Deprecated("Used on Android versions without predictive back")
    override fun onBackPressed() {
        handleBack()
    }

    /** The bottom bar blurs the grids scrolling behind it. */
    /**
     * The grid's columns are whole pixels, so the last column can end a few pixels before the
     * screen edge. Pull the bottom bar's right edge in to match it exactly (home and collections).
     */
    private fun alignBarToGrid(g: android.widget.GridView) {
        g.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (g.visibility != View.VISIBLE || g.childCount == 0) return@addOnLayoutChangeListener
            val inset = (6 * resources.displayMetrics.density).toInt()  // GlowFrame padding around each tile
            val right = (0 until g.childCount).maxOf { g.getChildAt(it).right } - inset + g.left
            val bar = findViewById<View>(R.id.photoBar)
            val area = bar.parent as View
            val margin = (area.width - right).coerceIn(0, inset)
            val lp = bar.layoutParams as android.view.ViewGroup.MarginLayoutParams
            if (lp.rightMargin != margin) { lp.rightMargin = margin; bar.layoutParams = lp }
        }
    }

    fun setUpBlur() {
        findViewById<BlurBar>(R.id.photoBar).blurBehind(grid, findViewById(R.id.photoGrid))
    }

    private fun countOf(lib: Library, c: LibraryCollection): Int = c.count

    /** Selected photos in a collection (in 4K mode, photos under 4K don't count). */
    private fun onOf(lib: Library, c: LibraryCollection): Int = WallpaperLibrary.onCountIn(lib, c.id)

    /** Bold title with its item count after it in grey, e.g. "Mountains  86". */
    private fun barTitle(title: String, count: Int): android.text.SpannableStringBuilder =
        android.text.SpannableStringBuilder(title).apply {
            setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, length, 0)
            val start = length
            append("  $count")
            setSpan(android.text.style.ForegroundColorSpan(0xFF938F99.toInt()), start, length, 0)
        }

    /** Bottom bar with nothing in the grid focused: totals for the tab, or for the open collection. */
    private fun showTotals() {
        val meta = findViewById<TextView>(R.id.meta)
        val check = findViewById<ImageView>(R.id.metaCheck)
        val lib = library
        if (lib == null) {
            check.visibility = View.INVISIBLE
            Meta.show(meta, "Loading library…")
            return
        }
        highlightHeader(false)
        if (openCollectionId != null) {
            Meta.show(meta, barTitle(pane.name(), pane.size()).append("\n").append(pane.barCounts()))
            check.setImageResource(when (pane.selectLabel()) {
                "Select: all" -> R.drawable.check_full
                "Select: none" -> R.drawable.check_off
                else -> R.drawable.check_partial
            })
            check.visibility = View.VISIBLE
            return
        }
        // Empty and hidden collections aren't counted.
        val all = collections.filter { !it.season && it.count > 0 && it.id !in WallpaperLibrary.hiddenCollections }
        val ons = all.map { it to onOf(lib, it) }
        val full = ons.count { (c, on) -> on > 0 && on >= countOf(lib, c) }
        val partial = ons.count { (c, on) -> on > 0 && on < countOf(lib, c) }
        val none = ons.count { (_, on) -> on == 0 }
        val n = all.size
        val parts = mutableListOf("$full selected")
        if (partial > 0) parts += "$partial partially selected"
        // With nothing selected at all, "0 selected" says it all.
        if (none > 0 && full + partial > 0) parts += "$none not selected"
        Meta.show(meta, barTitle("Collections", n).append("\n")
            .append(parts.joinToString("  ·  ")))
        check.setImageResource(when { full == n && n > 0 -> R.drawable.check_full; full + partial == 0 -> R.drawable.check_off; else -> R.drawable.check_partial })
        check.visibility = View.VISIBLE
    }

    /** Bar follows focus: the focused tile when in a grid, otherwise the totals. */
    private fun updateBar() {
        if (Overlay.isOpen(this)) return  // a menu is open: keep the bar as it was
        val photoGrid = findViewById<FocusGridView>(R.id.photoGrid)
        when {
            openCollectionId != null && photoGrid.hasFocus() -> pane.showSelected()
            openCollectionId == null && grid.hasFocus() -> showCardInfo(grid.selectedItemPosition.coerceAtLeast(0))
            else -> showTotals()
        }
    }

    /** Bottom bar on home: name and counts of the focused collection. */
    private fun showCardInfo(position: Int) {
        if (openCollectionId != null) return
        if (!grid.hasFocus()) return showTotals()
        val meta = findViewById<TextView>(R.id.meta)
        val check = findViewById<ImageView>(R.id.metaCheck)
        val lib = library
        val c = shown.getOrNull(position)
        if (lib == null || c == null) {
            check.visibility = View.INVISIBLE
            Meta.show(meta, if (lib == null) "Loading library…" else "No collections here.")
            return
        }
        highlightHeader(isHeader(c))
        if (isHeader(c)) return showSeasonsInfo(lib)
        if (isBlank(c)) return
        val on = onOf(lib, c)
        val waiting = WallpaperLibrary.notShownYetIn(WallpaperLibrary.itemsIn(lib, c.id).map { it.path }.toSet())
        val name = barTitle(c.name, countOf(lib, c)).apply {
            if (c.season) { append("   "); Meta.chip(this@SettingsActivity, this, Meta.Chip.SEASON, "Season") }
            if (c.id in WallpaperLibrary.hiddenCollections) append("  ·  hidden")
        }
        // A season: how many of its photos it shares with each other season.
        val shared = if (!c.season) "" else lib.collections.filter { it.season && it.id != c.id }.mapNotNull { o ->
            val n = lib.items.count { c.id in it.memberships && o.id in it.memberships }
            if (n > 0) n to o.name else null
        }.mapIndexed { i, (n, name) -> if (i == 0) "$n shared with $name" else "$n with $name" }
            .joinToString(", ").let { if (it.isEmpty()) "" else "  ·  $it" }
        Meta.show(meta, name.append("\n").append(
            (if (on == 0) "0 selected" else "$on selected  ·  $waiting yet to show this round") + shared))
        check.setImageResource(when { on == 0 -> R.drawable.check_off; on >= countOf(lib, c) -> R.drawable.check_full; else -> R.drawable.check_partial })
        check.visibility = View.VISIBLE
    }

    /** Hold OK on a collection: select / deselect it, hide / unhide it. */
    private fun showCollectionMenu(position: Int) {
        val c = shown.getOrNull(position) ?: return
        val selected = c.id !in WallpaperLibrary.disabledCollections
        val hidden = c.id in WallpaperLibrary.hiddenCollections
        val lib = library ?: return
        MenuDialog.show(this, c.name, info = Meta.collectionInfo(c, lib), rows = listOfNotNull(
            MenuDialog.Row(if (selected) R.drawable.ic_deselect else R.drawable.check_full,
                if (selected) "Deselect" else "Select") { toggleCollection(position) },
            // While marking photos (Edit multiple), a card marks or unmarks its whole collection.
            if (pane.bulk) {
                val all = pane.allMarkedIn(lib, c.id)
                MenuDialog.Row(if (all) R.drawable.mark_off else R.drawable.mark_on,
                    if (all) "Unmark whole collection" else "Mark whole collection") {
                    pane.markCollection(lib, c.id, !all); adapter.notifyDataSetChanged()
                }
            } else MenuDialog.Row(R.drawable.ic_edit_multiple, "Edit multiple") { startCardBulk(c.id) },
            if (pane.bulk) MenuDialog.Row(R.drawable.ic_pill_close, "Quit editing multiple") { endBulk() } else null,
            MenuDialog.Row(R.drawable.ic_pill_play, "Slideshow") {
                startActivity(android.content.Intent(this, PreviewActivity::class.java)
                    .putExtra(PreviewActivity.EXTRA_SLIDESHOW, true)
                    .putExtra(CollectionPane.EXTRA_ID, c.id))
            },
            MenuDialog.Row(if (hidden) R.drawable.ic_visibility else R.drawable.ic_visibility_off,
                if (hidden) "Unhide collection" else "Hide collection") {
                val h = WallpaperLibrary.hiddenCollections
                WallpaperLibrary.hiddenCollections = if (hidden) h - c.id else h + c.id
                // Hiding also deselects the collection. Its photos stay selected in their other
                // collections (only this collection is switched off). Unhiding keeps it deselected.
                if (!hidden) {
                    WallpaperLibrary.disabledCollections = WallpaperLibrary.disabledCollections + c.id
                    settingsChanged = true
                }
                refresh()
                if (shown.isNotEmpty()) grid.setSelection(position.coerceAtMost(shown.size - 1))
            }
        ))
    }

    private fun toggleCollection(position: Int) {
        val c = shown.getOrNull(position) ?: return
        val lib = library ?: return
        WallpaperLibrary.setCollection(lib, c.id, c.id in WallpaperLibrary.disabledCollections)
        settingsChanged = true
        refresh()
    }

    private fun installUpdate() {
        val u = update ?: return
        updateButton.isEnabled = false
        Thread {
            try {
                val file = UpdateChecker.download(this, u) { pct ->
                    runOnUiThread { updateButton.text = "Downloading… $pct%" }
                }
                runOnUiThread {
                    updateButton.isEnabled = true
                    updateButton.text = "Install v${u.version}"
                    UpdateChecker.install(this, file)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    updateButton.isEnabled = true
                    updateButton.text = "Update to v${u.version}"
                    Toast.makeText(this, "Download failed. Try again.", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun isInstalled(packageName: String): Boolean = try {
        packageManager.getApplicationInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private inner class CardAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int): Any? = shown.getOrNull(position)
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 3
        override fun getItemViewType(position: Int) = shown.getOrNull(position).let {
            when { it == null -> 0; isHeader(it) -> 1; isBlank(it) -> 2; else -> 0 }
        }
        override fun isEnabled(position: Int) = shown.getOrNull(position)?.let { !isBlank(it) } ?: true
        override fun areAllItemsEnabled() = false

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val entry = shown.getOrNull(position)
            // Blank cells are invisible cards, so every row keeps the card height.
            if (entry != null && isBlank(entry)) return (convertView ?: layoutInflater.inflate(R.layout.item_card, parent, false))
                .apply { visibility = View.INVISIBLE; isFocusable = false }
            if (entry != null && isHeader(entry)) return headerCell(position, convertView, parent)
            val view = convertView ?: layoutInflater.inflate(R.layout.item_card, parent, false).also {
                // Round the image's corners (16dp, Material 3) to match the focus ring.
                val cover = it.findViewById<View>(R.id.cover)
                cover.outlineProvider = ViewOutlineProvider.BACKGROUND
                cover.clipToOutline = true
            }
            // Zoom around the image centre (like photo tiles), so the picture grows in place.
            view.pivotY = 52 * resources.displayMetrics.density
            view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> v.pivotX = (v.width / 2).toFloat() }
            val cover = view.findViewById<ImageView>(R.id.cover)
            val name = view.findViewById<TextView>(R.id.name)
            val count = view.findViewById<TextView>(R.id.count)
            val check = view.findViewById<ImageView>(R.id.check)
            val body = view.findViewById<View>(R.id.body)

            val c = shown[position]
            val lib = library
            val selected = c.id !in WallpaperLibrary.disabledCollections
            name.text = c.name
            view.findViewById<View>(R.id.seasonChip).visibility = if (c.season) View.VISIBLE else View.GONE
            count.text = when {
                lib == null -> "${c.count}"
                !selected -> "Not selected  ·  ${countOf(lib, c)}"
                else -> "${onOf(lib, c)} of ${countOf(lib, c)} selected"
            }
            // Full green = every photo selected; outline = only some of them.
            val allOn = lib == null || onOf(lib, c) >= countOf(lib, c)
            check.setImageResource(if (allOn) R.drawable.check_full else R.drawable.check_partial)
            check.visibility = if (selected) View.VISIBLE else View.GONE
            body.alpha = if (selected) 1f else 0.4f
            view.findViewById<ImageView>(R.id.mark).apply {
                val lib = library
                visibility = if (cardBulk || (pane.bulk && lib != null)) View.VISIBLE else View.GONE
                val on = if (cardBulk) c.id in markedCards else lib != null && pane.allMarkedIn(lib, c.id)
                setImageResource(if (on) R.drawable.mark_on else R.drawable.mark_off)
            }

            val coverItem = covers[c.id]
            view.findViewById<View>(R.id.emptyIcon).visibility = if (lib != null && c.count == 0) View.VISIBLE else View.GONE
            if (lib != null && coverItem != null) {
                ImageLoader.load(
                    cover, WallpaperLibrary.url(lib, coverItem.thumbPath), 480,
                    fallbackUrl = WallpaperLibrary.url(lib, coverItem.path)
                )
            } else {
                cover.tag = null
                cover.setImageDrawable(null)
            }
            return view
        }
    }

    // ---- Edit multiple ----

    private var cardBulk = false
    private val markedCards = linkedSetOf<String>()

    private fun startCardBulk(first: String) {
        pane.endBulk()
        cardBulk = true
        markedCards.clear()
        markedCards += first
        adapter.notifyDataSetChanged()
        updateBulkUi()
    }

    private fun endBulk() {
        if (pane.bulk) { pane.endBulk(); return }
        if (!cardBulk) return
        cardBulk = false
        markedCards.clear()
        adapter.notifyDataSetChanged()
        updateBulkUi()
    }

    /** While marking, Done / Mark all / Edit marked replace Select, Show, Sort and Slideshow. */
    private fun updateBulkUi() {
        val bulk = pane.bulk || cardBulk
        val focusWasInRow = findViewById<View>(R.id.select).hasFocus() || findViewById<View>(R.id.bulkDone).hasFocus() ||
            findViewById<View>(R.id.bulkMark).hasFocus() || findViewById<View>(R.id.bulkActions).hasFocus()
        for (id in listOf(R.id.select, R.id.view, R.id.sort, R.id.slideshow))
            findViewById<View>(id).visibility = if (bulk) View.GONE else View.VISIBLE
        for (id in listOf(R.id.bulkDone, R.id.bulkMark, R.id.bulkActions))
            findViewById<View>(id).visibility = if (bulk) View.VISIBLE else View.GONE
        if (!bulk) {
            if (focusWasInRow) selectButton.requestFocus()
            refresh()
            return
        }
        if (focusWasInRow) findViewById<View>(R.id.bulkDone).requestFocus()
        if (openCollectionId == null) adapter.notifyDataSetChanged()  // card marks follow the photo marks
        bulkText()
    }

    private fun bulkText() {
        val n: Int
        val all: Boolean
        val photoMarks = pane.bulk && !cardBulk
        // Photo marks at home: "Mark all" only works inside a collection.
        if (photoMarks) { n = pane.markedCount(); all = if (openCollectionId != null) pane.allMarked() else pane.allLibraryMarked() }
        else {
            val ids = shown.filter { !isHeader(it) && !isBlank(it) }.map { it.id }
            n = markedCards.size; all = ids.isNotEmpty() && markedCards.containsAll(ids)
        }
        findViewById<Button>(R.id.bulkMark).text = if (all) "Unmark all" else "Mark all"
        total.text = "$n marked  ·  OK: mark  ·  Hold OK: options"
    }

    private fun showCardBulkMenu() {
        val lib = library ?: return
        val marked = collections.filter { it.id in markedCards }
        if (marked.isEmpty()) { Toast.makeText(this, "Mark some collections first (OK on a card)", Toast.LENGTH_SHORT).show(); return }
        fun done() { settingsChanged = true; refresh(); updateBulkUi() }
        MenuDialog.show(this, "${marked.size} marked", listOf(
            MenuDialog.Row(R.drawable.check_full, "Select all ${marked.size}") {
                marked.forEach { WallpaperLibrary.setCollection(lib, it.id, true) }; done()
            },
            MenuDialog.Row(R.drawable.ic_deselect, "Deselect all ${marked.size}") {
                marked.forEach { WallpaperLibrary.setCollection(lib, it.id, false) }; done()
            },
            MenuDialog.Row(R.drawable.ic_pill_close, "Quit editing multiple") { endBulk() },
            MenuDialog.Row(R.drawable.ic_visibility_off, "Hide all ${marked.size}") {
                WallpaperLibrary.hiddenCollections = WallpaperLibrary.hiddenCollections + marked.map { it.id }
                WallpaperLibrary.disabledCollections = WallpaperLibrary.disabledCollections + marked.map { it.id }
                done()
            },
            MenuDialog.Row(R.drawable.ic_visibility, "Unhide all ${marked.size}") {
                WallpaperLibrary.hiddenCollections = WallpaperLibrary.hiddenCollections - marked.map { it.id }.toSet()
                done()
            }
        ), info = android.text.SpannableStringBuilder("MARKED\n").apply {
            setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, length, 0)
            append(marked.joinToString(", ") { it.name })
            append("\n${marked.sumOf { it.count }} photos (a photo in two of them counts twice)")
        })
    }

    // ---- Seasons header row on home ----

    private fun isHeader(c: LibraryCollection) = c === HEADER
    private fun isBlank(c: LibraryCollection) = c === BLANK

    private enum class SeasonMode(val label: String) { ON("On"), OFF("Off") }

    /**
     * The Seasons row: On = the seasons you keep selected (turning it on selects all four; deselect
     * any you don't want), Off = no seasonal photos. Seasonal photos only show through a selected season.
     */
    private fun seasonMode(lib: Library): SeasonMode {
        if (!WallpaperLibrary.strictSeasons) WallpaperLibrary.strictSeasons = true
        val seasons = lib.collections.filter { it.season }.map { it.id }
        return if (seasons.all { it in WallpaperLibrary.disabledCollections }) SeasonMode.OFF else SeasonMode.ON
    }

    /** OK on the header: On ↔ Off. */
    private fun cycleSeasons() {
        val lib = library ?: return
        val on = seasonMode(lib) == SeasonMode.OFF
        lib.collections.filter { it.season }.forEach { WallpaperLibrary.setCollection(lib, it.id, on) }
        settingsChanged = true
        refresh()
        showCardInfo(grid.selectedItemPosition)
    }

    private fun headerCell(position: Int, convertView: View?, parent: ViewGroup): View {
        val v = convertView ?: layoutInflater.inflate(R.layout.item_season_header, parent, false)
        v.tag = "header"
        val cols = grid.numColumns.coerceAtLeast(1)
        val col = (position - shown.indexOfFirst { isHeader(it) }) % cols
        val text = v.findViewById<TextView>(R.id.headerText)
        val lib = library
        text.text = when (col) {
            0 -> "Seasons"
            cols - 1 -> lib?.let { seasonMode(it).label } ?: ""
            else -> ""
        }
        text.gravity = android.view.Gravity.CENTER_VERTICAL or (if (col == cols - 1) android.view.Gravity.END else android.view.Gravity.START)
        text.setCompoundDrawablesWithIntrinsicBounds(if (col == 0) R.drawable.ic_chip_season else 0, 0, 0, 0)
        text.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.chip_on_season))
        return v
    }

    private fun themeColor(attr: Int): Int {
        val a = obtainStyledAttributes(intArrayOf(attr)); val c = a.getColor(0, 0xFF4A4458.toInt()); a.recycle(); return c
    }

    /** The whole header row lights up as one button. */
    private fun highlightHeader(on: Boolean) {
        val g = grid as FocusGridView
        g.groupColor = themeColor(R.attr.mvTonal)
        if (g.groupLit != on) { g.groupLit = on; g.invalidate() }
    }

    /** Bottom bar on the header: what the setting does, and the seasonal photo counts. */
    private fun showSeasonsInfo(lib: Library) {
        val meta = findViewById<TextView>(R.id.meta)
        val check = findViewById<ImageView>(R.id.metaCheck)
        val seasons = lib.collections.filter { it.season }
        val ids = seasons.map { it.id }.toSet()
        val tagged = lib.items.filter { item -> item.memberships.any { it in ids } }
        val on = tagged.count { item -> item.memberships.any { it in ids && WallpaperLibrary.isSelectedIn(item, it) } }
        val waiting = WallpaperLibrary.notShownYetIn(tagged.map { it.path }.toSet())
        val twice = tagged.count { item -> item.memberships.count { it in ids } > 1 }
        val mode = seasonMode(lib)
        val explain = when (mode) {
            SeasonMode.ON -> "On: your selection of seasons to show"
            SeasonMode.OFF -> "Off: no seasonal photos will be shown"
        }
        val line = barTitle("Seasons", seasons.size).append("  ·  $explain")
        val parts = mutableListOf("$on of ${tagged.size} seasonal photos selected")
        if (on > 0) parts += "$waiting yet to show this round"
        if (twice > 0) parts += "$twice in 2+ seasons"
        Meta.show(meta, line.append("\n").append(parts.joinToString("  ·  ")))
        check.setImageResource(when (mode) { SeasonMode.OFF -> R.drawable.check_off; else -> R.drawable.check_full })
        check.visibility = View.VISIBLE
    }

    companion object {
        private val HEADER = LibraryCollection("__seasons_header", "Seasons", 0)
        private val BLANK = LibraryCollection("__blank", "", 0)
        private const val PROJECTIVY_PACKAGE_ID = "com.spocky.projengmenu"
        private const val HOME_HINT = "OK: open · Hold OK: options"
        /** Set when the colour theme changes, so the restarted screen opens on Settings again. */
        var reopenSettings = false
    }
}
