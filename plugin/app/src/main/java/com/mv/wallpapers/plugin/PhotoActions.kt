package com.mv.wallpapers.plugin

import android.app.Activity
import android.widget.Toast

/**
 * The long-press menu for one photo (full-screen preview and the photo grid):
 * select/deselect, move, edit tags, delete. Changes go to GitHub.
 * [onDone] gets the photo's new path after a move/tag edit, or null after a delete.
 */
object PhotoActions {
    fun show(
        activity: Activity,
        library: Library,
        item: LibraryItem,
        collectionId: String,
        extra: List<MenuDialog.Row> = emptyList(),
        onSelect: () -> Unit,
        onEditMultiple: (() -> Unit)? = null,
        onDone: (newPath: String?) -> Unit
    ) {
        val selected = WallpaperLibrary.isSelectedIn(item, collectionId)
        val rows = listOfNotNull(
            MenuDialog.Row(if (selected) R.drawable.ic_deselect else R.drawable.check_full,
                if (selected) "Deselect" else "Select") { onSelect() },
            onEditMultiple?.let { MenuDialog.Row(R.drawable.ic_edit_multiple, "Edit multiple") { it() } }
        ) + extra + listOf(
            MenuDialog.Row(R.drawable.ic_wallpaper, "Set as wallpaper") { setAsWallpaper(activity, library, listOf(item)) { onDone(item.path) } },
            MenuDialog.Row(R.drawable.ic_folder_move, "Move to collection", children = { moveTargets(activity, library, item, onDone) }),
            tagEditor(activity, library, item, onDone),
            MenuDialog.Row(R.drawable.ic_warning, "Delete from GitHub", danger = true) { confirmDelete(activity, item, onDone) }
        )
        MenuDialog.show(activity, "Options", rows, info = Meta.photoInfo(activity, item, library))
    }

    /**
     * Set as wallpaper: these photos (up to 50) become the only selected ones and go to
     * Projectivy right away. Every counter shows them as the selection.
     */
    fun setAsWallpaper(activity: Activity, library: Library, items: List<LibraryItem>, onDone: () -> Unit = {}) {
        if (items.isEmpty()) return
        val chosen = items.take(WallpaperLibrary.MAX_SET)
        WallpaperLibrary.setAll(library, false)
        chosen.forEach { WallpaperLibrary.setPhotoIn(library, it.collection, it, true) }
        WallpaperLibrary.showNow(chosen.map { it.path })
        val more = if (items.size > chosen.size) " (the first ${chosen.size}, limit ${WallpaperLibrary.MAX_SET})" else ""
        Toast.makeText(activity, if (chosen.size == 1) "Set as wallpaper" else "${chosen.size} photos set as wallpapers$more",
            Toast.LENGTH_SHORT).show()
        onDone()
    }

    /**
     * Edit multiple: one menu for all the marked photos. Changes to GitHub go in one commit.
     * [onDone] runs after a GitHub change (the list is reloaded and the marks cleared).
     */
    fun showBulk(
        activity: Activity,
        library: Library,
        items: List<LibraryItem>,
        collectionId: String,
        onSelect: (Boolean) -> Unit,
        onQuit: () -> Unit,
        onDone: () -> Unit
    ) {
        if (items.isEmpty()) {
            MenuDialog.show(activity, "Edit multiple", listOf(
                MenuDialog.Row(R.drawable.ic_pill_close, "Quit editing multiple") { onQuit() }
            ), info = "Nothing marked yet: OK on a photo marks it.")
            return
        }
        val n = items.size
        val names = library.collections.associate { it.id to it.name }
        val rows = listOf(
            MenuDialog.Row(R.drawable.check_full, "Select all $n") { onSelect(true) },
            MenuDialog.Row(R.drawable.ic_deselect, "Deselect all $n") { onSelect(false) },
            MenuDialog.Row(R.drawable.ic_pill_close, "Quit editing multiple") { onQuit() },
            MenuDialog.Row(R.drawable.ic_wallpaper,
                if (n > WallpaperLibrary.MAX_SET) "Set as wallpaper (first ${WallpaperLibrary.MAX_SET})" else "Set as wallpaper") {
                setAsWallpaper(activity, library, items) { onSelect(true) }
            },
            MenuDialog.Row(R.drawable.ic_folder_move, "Move all to collection", children = {
                library.collections.filter { !it.season }.sortedBy { it.name.lowercase() }.map { c ->
                    MenuDialog.Row(R.drawable.ic_folder, c.name) action@{
                        if (needsToken(activity)) return@action
                        val changes = items.filter { it.collection != c.id }
                            .associate { it.path to newPath(it, c.id, it.tags.filter { t -> t != c.id }) }
                        commitBulk(activity, changes, items, "Moving ${changes.size} photos to ${c.name}…",
                            "Moved ${changes.size} photos to ${c.name}", "Move ${changes.size} photos to ${c.id} (from TV)", onDone)
                    }
                }
            }),
            bulkTagEditor(activity, library, items, onDone),
            MenuDialog.Row(R.drawable.ic_warning, "Delete $n from GitHub", danger = true) action@{
                if (needsToken(activity)) return@action
                Popup.show(activity, "Delete $n photos?",
                    "They're removed from GitHub in one change and disappear from every TV within a few minutes. They can still be restored from the repo's history.",
                    icon = R.drawable.ic_warning, confirm = "Delete $n", danger = true) {
                    commitBulk(activity, items.associate { it.path to null }, items, "Deleting $n photos…",
                        "Deleted $n photos", "Delete $n photos (from TV)", onDone)
                }
            }
        )
        val inFolders = items.groupingBy { it.collection }.eachCount().entries.sortedByDescending { it.value }
            .joinToString(", ") { "${it.value} in ${names[it.key] ?: it.key}" }
        val info = android.text.SpannableStringBuilder().apply {
            append("MARKED\n")
            setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, length, 0)
            append("$n marked  ·  ${items.count { WallpaperLibrary.isSelectedIn(it, collectionId) }} selected\n")
            append(inFolders)
            append("\n\nSet as wallpaper limit: ${WallpaperLibrary.MAX_SET}")
        }
        MenuDialog.show(activity, "$n marked", rows, info = info)
    }

    /**
     * Tags for all marked photos. A tag only some of them have shows as partly ticked and stays
     * as it is unless you change it. The main folder isn't changed here (use Move).
     */
    private fun bulkTagEditor(activity: Activity, lib: Library, items: List<LibraryItem>, onDone: () -> Unit): MenuDialog.Row {
        val folders = lib.collections.filter { !it.season }.sortedBy { it.name.lowercase() }
        val seasons = lib.collections.filter { it.season }
            .sortedBy { WallpaperLibrary.SEASON_ORDER.indexOf(it.id).let { i -> if (i < 0) 99 else i } }
        val candidates = folders + seasons
        // true = every marked photo, false = none, null = some (left alone)
        val start = candidates.associate { c ->
            val has = items.count { c.id in it.tags }
            c.id to when (has) { 0 -> false; items.size -> true; else -> null }
        }
        val state = start.toMutableMap()
        return MenuDialog.Row(R.drawable.ic_label, "Edit tags of all", children = {
            listOf(MenuDialog.Row(R.drawable.ic_folder, "Main folders stay (use Move to change them)", stay = true) {}) +
                candidates.map { c ->
                    val s = state[c.id]
                    MenuDialog.Row(when (s) { true -> R.drawable.check_white_on; false -> R.drawable.check_white_off; null -> R.drawable.check_partial },
                        (if (c.season) "${c.name}  (season)" else c.name) + if (s == null) "  (some)" else "",
                        stay = true, dividerBefore = c.id == seasons.firstOrNull()?.id) {
                        // some → all → none → all …
                        state[c.id] = state[c.id] != true
                    }
                }
        }, onLeave = {
            if (state == start) false
            else {
                if (!needsToken(activity)) {
                    val changes = items.mapNotNull { item ->
                        val tags = candidates.map { it.id }.filter { id ->
                            id != item.collection && (state[id] ?: (id in item.tags))
                        }
                        val path = newPath(item, item.collection, tags)
                        if (path == item.path) null else item.path to path
                    }.toMap()
                    if (changes.isEmpty()) Toast.makeText(activity, "Nothing to change", Toast.LENGTH_SHORT).show()
                    else commitBulk(activity, changes, items, "Saving tags for ${changes.size} photos…",
                        "Tags saved for ${changes.size} photos", "Tags for ${changes.size} photos (from TV)", onDone)
                }
                true
            }
        })
    }

    private fun newPath(item: LibraryItem, collection: String, tags: List<String>): String {
        val ext = item.fileName.substringAfterLast('.', "jpg")
        val base = item.fileName.substringBeforeLast('.').split("~").first()
        return "wallpapers/$collection/" + (listOf(base) + tags).joinToString("~") + "." + ext
    }

    /** One GitHub commit for many photos (moves/renames, or deletes when the new path is null). */
    private fun commitBulk(
        activity: Activity, changes: Map<String, String?>, items: List<LibraryItem>,
        working: String, done: String, message: String, onDone: () -> Unit
    ) {
        if (changes.isEmpty()) { Toast.makeText(activity, "Nothing to change", Toast.LENGTH_SHORT).show(); return }
        Toast.makeText(activity, working, Toast.LENGTH_SHORT).show()
        Thread {
            val error = GitHubApi.commitChanges(changes, message)
            activity.runOnUiThread {
                if (error != null && error != GitHubApi.ALREADY_GONE) {
                    Toast.makeText(activity, error, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                for ((old, new) in changes) {
                    if (new == null || error == GitHubApi.ALREADY_GONE) WallpaperLibrary.markDeleted(old)
                    else {
                        val folder = new.removePrefix("wallpapers/").substringBefore('/')
                        val tags = new.substringAfterLast('/').substringBeforeLast('.').split("~").drop(1)
                        WallpaperLibrary.markRenamed(old, new, folder, tags)
                    }
                }
                Toast.makeText(activity, if (error == null) done else "Already removed or renamed on GitHub. Refreshing…",
                    Toast.LENGTH_SHORT).show()
                onDone()
                if (error != null) Thread { WallpaperLibrary.load(activity.applicationContext, allowNetwork = true, fresh = true) }.start()
            }
        }.start()
    }

    private fun needsToken(activity: Activity): Boolean {
        if (GitHubApi.token != null) return false
        Toast.makeText(activity, "Add a GitHub token in Settings first", Toast.LENGTH_LONG).show()
        return true
    }

    /** Collections to move to (seasons are tags, not places), A–Z. */
    private fun moveTargets(activity: Activity, lib: Library, item: LibraryItem, onDone: (String?) -> Unit): List<MenuDialog.Row> =
        lib.collections.filter { it.id != item.collection && !it.season }
            .sortedBy { it.name.lowercase() }
            .map { c ->
                MenuDialog.Row(R.drawable.ic_folder, c.name) {
                    if (!needsToken(activity)) {
                        // Same title and tags; a tag for the new folder is dropped (it's the folder now).
                        val tags = item.tags.filter { it != c.id }
                        rename(activity, lib, item, c.id, tags, "Moving to ${c.name}…",
                            "Moved to ${c.name}", "Move ${item.fileName} to ${c.id} (from TV)", onDone)
                    }
                }
            }

    /** Tick the collections the photo should also be in, then Save. Back cancels. */
    /** Tick collections the photo should also be in; Back saves (if anything changed) and closes. */
    private fun tagEditor(activity: Activity, lib: Library, item: LibraryItem, onDone: (String?) -> Unit): MenuDialog.Row {
        val chosen = item.tags.toMutableSet()
        // Folders A–Z first, then seasons in their usual order.
        val candidates = lib.collections.filter { it.id != item.collection && !it.season }.sortedBy { it.name.lowercase() } +
            lib.collections.filter { it.id != item.collection && it.season }
                .sortedBy { WallpaperLibrary.SEASON_ORDER.indexOf(it.id).let { i -> if (i < 0) 99 else i } }
        val firstSeason = candidates.firstOrNull { it.season }?.id
        return MenuDialog.Row(
            R.drawable.ic_label, "Edit tags",
            children = {
                // The photo's own folder first: always ticked (change it with Move to collection).
                val main = lib.collections.firstOrNull { it.id == item.collection }
                listOfNotNull(main?.let {
                    MenuDialog.Row(R.drawable.check_white_on, "${it.name}  (main · use Move to change)", stay = true) {}
                }) + candidates.map { c ->
                    MenuDialog.Row(if (c.id in chosen) R.drawable.check_white_on else R.drawable.check_white_off,
                        if (c.season) "${c.name}  (season)" else c.name, stay = true, dividerBefore = c.id == firstSeason) {
                        if (c.id in chosen) chosen -= c.id else chosen += c.id
                    }
                }
            },
            onLeave = {
                if (chosen == item.tags.toSet()) false  // nothing changed: just go back
                else {
                    if (!needsToken(activity)) {
                        val ordered = candidates.map { it.id }.filter { it in chosen }
                        rename(activity, lib, item, item.collection, ordered, "Saving tags…", "Tags saved",
                            "Tags for ${item.fileName}: ${ordered.joinToString(", ").ifEmpty { "none" }} (from TV)", onDone)
                    }
                    true
                }
            }
        )
    }

    /** Moves/renames the file on GitHub; this TV shows the new version right away. */
    private fun rename(
        activity: Activity, lib: Library, item: LibraryItem, collection: String, tags: List<String>,
        working: String, done: String, message: String, onDone: (String?) -> Unit
    ) {
        val newPath = newPath(item, collection, tags)
        if (newPath == item.path) return
        Toast.makeText(activity, working, Toast.LENGTH_SHORT).show()
        Thread {
            val error = GitHubApi.moveFile(item.path, newPath, "", message)
            activity.runOnUiThread {
                if (error != null) {
                    Toast.makeText(activity, error, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                WallpaperLibrary.markRenamed(item.path, newPath, collection, tags)
                Toast.makeText(activity, done, Toast.LENGTH_SHORT).show()
                onDone(newPath)
            }
        }.start()
    }

    private fun confirmDelete(activity: Activity, item: LibraryItem, onDone: (String?) -> Unit) {
        if (needsToken(activity)) return
        Popup.show(
            activity, "Delete this photo?",
            "${item.path}\n\nIt's removed from GitHub and disappears from every TV within a few minutes. It can still be restored from the repo's history.",
            icon = R.drawable.ic_warning, confirm = "Delete", danger = true
        ) { delete(activity, item, onDone) }
    }

    private fun delete(activity: Activity, item: LibraryItem, onDone: (String?) -> Unit) {
        Toast.makeText(activity, "Deleting…", Toast.LENGTH_SHORT).show()
        Thread {
            val error = GitHubApi.deleteFile(item.path)
            activity.runOnUiThread {
                if (error == GitHubApi.ALREADY_GONE) {
                    // Not on GitHub under this name any more (deleted, or renamed by a tag edit or clean-up):
                    // hide it here and fetch the current list, which shows its new name if it has one.
                    WallpaperLibrary.markDeleted(item.path)
                    Toast.makeText(activity, "This photo was already removed or renamed on GitHub. Refreshing the list…", Toast.LENGTH_LONG).show()
                    onDone(null)
                    Thread { WallpaperLibrary.load(activity.applicationContext, allowNetwork = true, fresh = true) }.start()
                    return@runOnUiThread
                }
                if (error != null) {
                    Toast.makeText(activity, error, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                // Hide it right away everywhere on this TV; GitHub's list catches up in a few minutes.
                WallpaperLibrary.markDeleted(item.path)
                Toast.makeText(activity, "Deleted ${item.fileName}", Toast.LENGTH_SHORT).show()
                onDone(null)
            }
        }.start()
    }
}
