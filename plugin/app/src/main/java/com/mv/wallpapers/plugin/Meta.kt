package com.mv.wallpapers.plugin

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import java.util.Locale

/** Photo details for the grid footer and the full-screen preview. */
object Meta {
    /**
     * Line 1: Title by Author, then a folder chip, a tags chip and a season chip.
     * Line 2: resolution · file size · file name.
     */
    fun describe(context: android.content.Context, item: LibraryItem, library: Library): CharSequence {
        val names = library.collections.associate { it.id to it.name }
        val grey = 0xFFB4B2A9.toInt()
        val text = SpannableStringBuilder(item.title)
        fun span(color: Int, s: String) {
            val start = text.length
            text.append(s)
            text.setSpan(ForegroundColorSpan(color), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        item.author?.let { span(grey, " by $it") }
        // Folder chip (theme colour), a chip with its other collections, a season chip.
        text.append("   ")
        chip(context, text, Chip.FOLDER, names[item.collection] ?: item.collection)
        val order = library.collections.map { it.id }
        val seasonIds = library.collections.filter { it.season }.map { it.id }.toSet()
        val tags = item.tags.filter { it != item.collection }.sortedBy { order.indexOf(it) }
        val (seasons, others) = tags.partition { it in seasonIds }
        if (others.isNotEmpty()) { text.append("   "); chip(context, text, Chip.TAG, others.joinToString(" · ") { names[it] ?: it }) }
        if (seasons.isNotEmpty()) { text.append("   "); chip(context, text, Chip.SEASON, seasons.joinToString(" · ") { names[it] ?: it }) }
        val line2 = mutableListOf("${item.width}×${item.height}")
        if (item.bytes > 0) line2 += String.format(Locale.US, "%.1f MB", item.bytes / 1_000_000.0)
        return text.append("\n").append(line2.joinToString("  ·  "))
    }

    /** Slideshow bar: just the title and the folder / tag / season chips, on one line. */
    fun compact(context: android.content.Context, item: LibraryItem, library: Library): CharSequence {
        val full = describe(context, item, library)
        val cut = full.indexOf('\n')
        val line = SpannableStringBuilder(if (cut < 0) full else full.subSequence(0, cut))
        // Drop " by Author" (grey span right after the title).
        item.author?.let { a ->
            val at = item.title.length
            val by = " by $a"
            if (line.length >= at + by.length && line.subSequence(at, at + by.length).toString() == by) line.delete(at, at + by.length)
        }
        return line
    }

    private fun bold(text: SpannableStringBuilder, s: String) {
        val start = text.length
        text.append(s)
        text.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    fun date(seconds: Long): String? {
        if (seconds <= 0) return null
        val ms = if (seconds < 100_000_000_000L) seconds * 1000 else seconds
        return java.text.SimpleDateFormat("d MMM yyyy", Locale.UK).format(java.util.Date(ms))
    }

    /** Info block under the buttons of a photo's Hold-OK menu: title by author, chips, then details. */
    fun photoInfo(context: android.content.Context, item: LibraryItem, library: Library): CharSequence {
        val full = describe(context, item, library)
        val cut = full.indexOf('\n')
        val head = SpannableStringBuilder(if (cut < 0) full else full.subSequence(0, cut))
        // Title (bold) and "by Author" on the first line, the chips on the next.
        val by = item.author?.let { " by $it" } ?: ""
        val split = (item.title.length + by.length).coerceAtMost(head.length)
        head.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, item.title.length.coerceAtMost(head.length), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        head.replace(split, (split + 3).coerceAtMost(head.length), "\n")
        val t = SpannableStringBuilder()
        bold(t, "PHOTO INFO\n")
        t.append(head)
        t.append("\n${item.width}×${item.height}")
        if (WallpaperLibrary.is4k(item)) t.append("  (4K)")
        if (item.bytes > 0) t.append(String.format(Locale.US, "  ·  %.1f MB", item.bytes / 1_000_000.0))
        date(item.added)?.let { t.append("\nAdded: $it") }
        t.append("\n${item.fileName}")
        return t
    }

    /** Info block under the buttons of a collection's Hold-OK menu. */
    fun collectionInfo(c: LibraryCollection, library: Library): CharSequence {
        val items = WallpaperLibrary.itemsIn(library, c.id)
        val on = WallpaperLibrary.onCountIn(library, c.id)
        val t = SpannableStringBuilder()
        bold(t, if (c.season) "SEASON INFO\n" else "COLLECTION INFO\n")
        bold(t, "${items.size}"); t.append(" photos\n")
        bold(t, "$on"); t.append(" selected  ·  ")
        bold(t, "${WallpaperLibrary.notShownYetIn(items.map { it.path }.toSet())}"); t.append(" yet to show this round\n")
        bold(t, "${items.count { WallpaperLibrary.is4k(it) }}"); t.append(" at 2160p+ (4K)\n")
        bold(t, "${items.count { !WallpaperLibrary.is4k(it) && minOf(it.height, it.width * 9 / 16) >= 1080 }}"); t.append(" at 1080p+ (<2160p)")
        if (!c.season) {
            val tagged = library.items.count { c.id in it.tags && it.collection != c.id }
            if (tagged > 0) { t.append("\n"); bold(t, "$tagged"); t.append(" tagged in from other folders") }
        }
        return t
    }

    enum class Chip { FOLDER, TAG, SEASON }

    /** Appends an inline chip like the ones on the photo tiles. Tags and seasons have fixed colours. */
    fun chip(context: android.content.Context, text: SpannableStringBuilder, kind: Chip, label: String) {
        val (bg, fg) = when (kind) {
            Chip.FOLDER -> {
                val a = context.obtainStyledAttributes(intArrayOf(R.attr.mvTonalTranslucent, R.attr.mvOnTonal))
                (a.getColor(0, 0xE64A4458.toInt()) to a.getColor(1, 0xFFE8DEF8.toInt())).also { a.recycle() }
            }
            Chip.TAG -> context.getColor(R.color.chip_tag) to context.getColor(R.color.chip_on_tag)
            Chip.SEASON -> context.getColor(R.color.chip_season) to context.getColor(R.color.chip_on_season)
        }
        val icon = when (kind) {
            Chip.FOLDER -> R.drawable.ic_chip_folder
            Chip.TAG -> R.drawable.ic_chip_tag
            Chip.SEASON -> R.drawable.ic_chip_season
        }
        val start = text.length
        text.append(label)
        text.setSpan(ChipSpan(context.getDrawable(icon)!!.mutate(), label, bg, fg, context.resources.displayMetrics.density),
            start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** Shows [text] in [first] and, after a line break, in the meta2 line under it. Long lines scroll. */
    fun show(first: android.widget.TextView, text: CharSequence) {
        val second = (first.parent as android.view.View).findViewById<android.widget.TextView>(R.id.meta2)
        val cut = text.indexOf('\n')
        first.text = if (cut < 0) text else text.subSequence(0, cut)
        second?.text = if (cut < 0) "" else text.subSequence(cut + 1, text.length)
        first.isSelected = true
        second?.isSelected = true
    }

    /** Green check when selected in this collection, grey when not. */
    fun checkIcon(item: LibraryItem, collectionId: String): Int =
        if (WallpaperLibrary.isSelectedIn(item, collectionId)) R.drawable.check_full else R.drawable.check_off
}
